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

import io.reactivex.Maybe;

import org.apache.cassandra.auth.permission.CorePermission;
import org.apache.cassandra.cql3.CFName;
import org.apache.cassandra.db.view.View;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.schema.MigrationManager;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.Event;

public class DropViewStatement extends SchemaAlteringStatement
{
    public final boolean ifExists;

    public DropViewStatement(CFName cf, boolean ifExists)
    {
        super(cf);
        this.ifExists = ifExists;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        TableMetadataRef baseTable = View.findBaseTable(keyspace(), columnFamily());
        if (baseTable != null)
            state.hasColumnFamilyAccess(keyspace(), baseTable.name, CorePermission.ALTER);
    }

    public void validate(ClientState state)
    {
        // validated in findIndexedCf()
    }

    public Maybe<Event.SchemaChange> announceMigration(QueryState queryState, boolean isLocalOnly) throws InvalidRequestException, ConfigurationException
    {
            return MigrationManager.announceViewDrop(keyspace(), columnFamily(), isLocalOnly)
                    .andThen(Maybe.just(new Event.SchemaChange(Event.SchemaChange.Change.DROPPED, Event.SchemaChange.Target.TABLE, keyspace(), columnFamily())))
                                   .onErrorResumeNext(e ->
                                                      {
                                                          if (e instanceof ConfigurationException && ifExists)
                                                              return Maybe.empty();

                                                          return Maybe.error(e);
                                                      });
    }
}
