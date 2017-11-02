/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.util.regex.Pattern;

import io.reactivex.Maybe;

import com.datastax.bdp.db.audit.AuditableEventType;
import com.datastax.bdp.db.audit.CoreAuditableEventType;

import org.apache.cassandra.auth.*;
import org.apache.cassandra.auth.permission.CorePermission;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.locator.LocalStrategy;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.MigrationManager;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.service.*;
import org.apache.cassandra.transport.Event;

/** A <code>CREATE KEYSPACE</code> statement parsed from a CQL query. */
public class CreateKeyspaceStatement extends SchemaAlteringStatement
{
    private static final Pattern PATTERN_WORD_CHARS = Pattern.compile("\\w+");

    private final String name;
    private final KeyspaceAttributes attrs;
    private final boolean ifNotExists;

    /**
     * Creates a new <code>CreateKeyspaceStatement</code> instance for a given
     * keyspace name and keyword arguments.
     *
     * @param name the name of the keyspace to create
     * @param attrs map of the raw keyword arguments that followed the <code>WITH</code> keyword.
     */
    public CreateKeyspaceStatement(String name, KeyspaceAttributes attrs, boolean ifNotExists)
    {
        super();
        this.name = name;
        this.attrs = attrs;
        this.ifNotExists = ifNotExists;
    }

    @Override
    public AuditableEventType getAuditEventType()
    {
        return CoreAuditableEventType.ADD_KS;
    }

    @Override
    public String keyspace()
    {
        return name;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasAllKeyspacesAccess(CorePermission.CREATE);
    }

    /**
     * The <code>CqlParser</code> only goes as far as extracting the keyword arguments
     * from these statements, so this method is responsible for processing and
     * validating.
     *
     * @throws InvalidRequestException if arguments are missing or unacceptable
     */
    public void validate(ClientState state) throws RequestValidationException
    {
        Schema.validateKeyspaceNotSystem(name);

        // keyspace name
        if (!PATTERN_WORD_CHARS.matcher(name).matches())
            throw new InvalidRequestException(String.format("\"%s\" is not a valid keyspace name", name));
        if (name.length() > SchemaConstants.NAME_LENGTH)
            throw new InvalidRequestException(String.format("Keyspace names shouldn't be more than %s characters long (got \"%s\")", SchemaConstants.NAME_LENGTH, name));

        attrs.validate();

        if (attrs.getReplicationStrategyClass() == null)
            throw new ConfigurationException("Missing mandatory replication strategy class");

        // The strategy is validated through KSMetaData.validate() in announceNewKeyspace below.
        // However, for backward compatibility with thrift, this doesn't validate unexpected options yet,
        // so doing proper validation here.
        KeyspaceParams params = attrs.asNewKeyspaceParams();
        params.validate(name);
        if (params.replication.klass.equals(LocalStrategy.class))
            throw new ConfigurationException("Unable to use given strategy class: LocalStrategy is reserved for internal use.");
    }

    public Maybe<Event.SchemaChange> announceMigration(QueryState queryState, boolean isLocalOnly) throws RequestValidationException
    {
        KeyspaceMetadata ksm = KeyspaceMetadata.create(name, attrs.asNewKeyspaceParams());
        return MigrationManager.announceNewKeyspace(ksm, isLocalOnly)
                               .andThen(Maybe.just(new Event.SchemaChange(Event.SchemaChange.Change.CREATED, keyspace())))
                               .onErrorResumeNext(e -> {
                                   if (e instanceof AlreadyExistsException && ifNotExists)
                                       return Maybe.empty();

                                   return Maybe.error(e);
                               });
    }

    protected void grantPermissionsToCreator(QueryState state)
    {
        try
        {
            IAuthorizer authorizer = DatabaseDescriptor.getAuthorizer();
            RoleResource role = RoleResource.role(state.getClientState().getUser().getName());
            DataResource keyspace = DataResource.keyspace(keyspace());
            authorizer.grant(AuthenticatedUser.SYSTEM_USER,
                             authorizer.applicablePermissions(keyspace),
                             keyspace,
                             role);
            FunctionResource functions = FunctionResource.keyspace(keyspace());
            authorizer.grant(AuthenticatedUser.SYSTEM_USER,
                             authorizer.applicablePermissions(functions),
                             functions,
                             role);
        }
        catch (RequestExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }
}
