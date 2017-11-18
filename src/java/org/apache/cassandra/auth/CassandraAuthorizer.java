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
package org.apache.cassandra.auth;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.auth.permission.Permissions;
import org.apache.cassandra.concurrent.TPCUtils;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.cql3.statements.*;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.serializers.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ResultMessage;

import static org.apache.cassandra.auth.AuthKeyspace.ROLE_PERMISSIONS;
import static org.apache.cassandra.schema.SchemaConstants.AUTH_KEYSPACE_NAME;

/**
 * CassandraAuthorizer is an IAuthorizer implementation that keeps
 * user permissions internally in C* using the system_auth.role_permissions
 * table.
 */
public class CassandraAuthorizer implements IAuthorizer
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraAuthorizer.class);

    private static final String ROLE = "role";
    private static final String RESOURCE = "resource";
    private static final String PERMISSIONS = "permissions";
    private static final String GRANTABLES = "grantables";
    private static final String RESTRICTED = "restricted";

    private static final String ROLE_PERMISSIONS_TABLE = AUTH_KEYSPACE_NAME + "." + ROLE_PERMISSIONS;
    
    private SelectStatement authorizeRoleStatement;

    public CassandraAuthorizer()
    {
    }

    @Override
    public PermissionSets allPermissionSets(AuthenticatedUser user, IResource resource)
    {
        if (user.isSuper())
            // superuser can do everything
            return PermissionSets.builder()
                                 .addGranted(applicablePermissions(resource))
                                 .addGrantables(applicablePermissions(resource))
                                 .build();

        try
        {
            return getPermissionsFor(user, resource);
        }
        catch (RequestValidationException e)
        {
            throw new AssertionError(e); // not supposed to happen
        }
        catch (RequestExecutionException e)
        {
            logger.warn("CassandraAuthorizer failed to authorize {} for {}", user, resource);
            throw e;
        }
    }

    public void grant(AuthenticatedUser performer, Set<Permission> permissions, IResource resource, RoleResource grantee, GrantMode grantMode)
    throws RequestValidationException, RequestExecutionException
    {
        grantRevoke(permissions, resource, grantee, grantMode, "+");
    }

    public void revoke(AuthenticatedUser performer, Set<Permission> permissions, IResource resource, RoleResource revokee, GrantMode grantMode)
    throws RequestValidationException, RequestExecutionException
    {
        grantRevoke(permissions, resource, revokee, grantMode, "-");
    }

    // Called when deleting a role with DROP ROLE query.
    // Internal hook, so no permission checks are needed here.
    public void revokeAllFrom(RoleResource revokee)
    {
        try
        {
            process("DELETE FROM " + ROLE_PERMISSIONS_TABLE + " WHERE role = '%s'",
                    escape(revokee.getRoleName()));
        }
        catch (RequestExecutionException | RequestValidationException e)
        {
            logger.warn("CassandraAuthorizer failed to revoke all permissions of {}: {}",
                        revokee.getRoleName(),
                        e.getMessage());
        }
    }

    // Called after a resource is removed (DROP KEYSPACE, DROP TABLE, etc.).
    // Execute a logged batch removing all the permissions for the resource.
    public void revokeAllOn(IResource droppedResource)
    {
        try
        {
            Set<String> roles = fetchRolesWithPermissionsOn(droppedResource);
            deletePermissionsFor(droppedResource, roles);
        }
        catch (RequestExecutionException | RequestValidationException e)
        {
            logger.warn("CassandraAuthorizer failed to revoke all permissions on {}: {}", droppedResource, e.getMessage());
        }
    }

    /**
     * Deletes all the permissions for the specified resource and roles.
     * @param resource the resource
     * @param roles the roles
     */
    private void deletePermissionsFor(IResource resource, Set<String> roles)
    {
        process("DELETE FROM " + ROLE_PERMISSIONS_TABLE + " WHERE role IN (%s) AND resource = '%s'",
                roles.stream()
                     .map(CassandraAuthorizer::escape)
                     .collect(Collectors.joining("', '", "'", "'")),
                escape(resource.getName()));
    }

    /**
     * Retrieves all the roles that have some permissions on the specified resource.
     * @param resource the resource for with the roles must be retrieved
     * @return the roles that have some permissions on the specified resource
     */
    private Set<String> fetchRolesWithPermissionsOn(IResource resource)
    {
        UntypedResultSet rows = 
                process("SELECT role FROM " + ROLE_PERMISSIONS_TABLE + " WHERE resource = '%s' ALLOW FILTERING",
                        escape(resource.getName()));

        Set<String> roles = new HashSet<>(rows.size());
        for (UntypedResultSet.Row row : rows)
            roles.add(row.getString("role"));

        return roles;
    }

    /**
     * Returns the permissions for the specified user and resource
     * @param user the user
     * @param resource the resource
     * @return the permissions for the specified user and resource
     */
    private PermissionSets getPermissionsFor(AuthenticatedUser user, IResource resource)
    {
        PermissionSets.Builder permissions = PermissionSets.builder();

        for (UntypedResultSet.Row row : fetchPermissions(user, resource))
            addPermissionsFromRow(row, permissions);

        return permissions.build();
    }

    /**
     * Fetch the permissions of the user for the specified resources from the {@code role_permissions} table. 
     * @param user the user
     * @param resource the resource
     * @return the permissions of the user for the resources
     */
    private UntypedResultSet fetchPermissions(AuthenticatedUser user, IResource resource)
    {
        // Query looks like this:
        //      SELECT permissions, restricted, grantables
        //        FROM system_auth.role-permissions
        //       WHERE resource = ? AND role IN ?
        // Purpose is to fetch permissions for all role with one query and not one query per role.

        ByteBuffer resourceName = UTF8Serializer.instance.serialize(resource.getName());
        ByteBuffer roleNames = ListSerializer.getInstance(UTF8Serializer.instance).serialize(user.getRoleNames());

        QueryOptions options = QueryOptions.forInternalCalls(ConsistencyLevel.LOCAL_ONE,
                                                             Lists.newArrayList(resourceName, roleNames));

        ResultMessage.Rows rows = TPCUtils.blockingGet(authorizeRoleStatement.execute(QueryState.forInternalCalls(), options, System.nanoTime()));
        return UntypedResultSet.create(rows.result);
    }

    private static void addPermissionsFromRow(UntypedResultSet.Row row, PermissionSets.Builder perms)
    {
        permissionsFromRow(row, PERMISSIONS, perms::addGranted);
        permissionsFromRow(row, RESTRICTED, perms::addRestricted);
        permissionsFromRow(row, GRANTABLES, perms::addGrantable);
    }

    private static void permissionsFromRow(UntypedResultSet.Row row, String column, Consumer<Permission> perms)
    {
        if (!row.has(column))
            return;
        row.getSet(column, UTF8Type.instance)
           .stream()
           .map(Permissions::permission)
           .forEach(perms);
    }

    private void grantRevoke(Set<Permission> permissions,
                             IResource resource,
                             RoleResource role,
                             GrantMode grantMode,
                             String op)
    {
        // Construct a CQL command like the following. The updated columns are variable (depend on grantMode).
        //
        //   UPDATE system_auth.role_permissions
        //      SET permissions = permissions + {<permissions>}
        //    WHERE role = <role-name>
        //      AND resource = <resource-name>
        //

        String column = columnForGrantMode(grantMode);

        String perms = permissions.stream()
                                  .map(Permission::getFullName)
                                  .collect(Collectors.joining("','", "'", "'"));

        process("UPDATE " + ROLE_PERMISSIONS_TABLE + " SET %s = %s %s { %s } WHERE role = '%s' AND resource = '%s'",
                column, column, op, perms, escape(role.getRoleName()), escape(resource.getName()));
    }

    private static String columnForGrantMode(GrantMode grantMode)
    {
        switch (grantMode)
        {
            case GRANT:
                return PERMISSIONS;
            case RESTRICT:
                return RESTRICTED;
            case GRANTABLE:
                return GRANTABLES;
            default:
                throw new AssertionError(); // make compiler happy
        }
    }

    public Set<PermissionDetails> list(Set<Permission> permissions,
                                       IResource resource,
                                       RoleResource grantee)
    {
        // 'grantee' can be null - in that case everyone's permissions have been requested. Otherwise only single user's.
        Set<RoleResource> roles = grantee != null
                                  ? DatabaseDescriptor.getRoleManager().getRoles(grantee, true)
                                  : Collections.emptySet();

        Set<PermissionDetails> details = new HashSet<>();
        // If it exists, try the legacy user permissions table first. This is to handle the case
        // where the cluster is being upgraded and so is running with mixed versions of the perms table
        for (UntypedResultSet.Row row : process(buildListQuery(resource, roles)))
        {
            PermissionSets.Builder permsBuilder = PermissionSets.builder();
            addPermissionsFromRow(row, permsBuilder);
            PermissionSets perms = permsBuilder.build();

            String rowRole = row.getString(ROLE);
            IResource rowResource = Resources.fromName(row.getString(RESOURCE));

            for (Permission p : perms.allContainedPermissions())
            {
                if (permissions.contains(p))
                {
                    details.add(new PermissionDetails(rowRole,
                                                      rowResource,
                                                      p,
                                                      perms.grantModesFor(p)));
                }
            }
        }
        return details;
    }

    private String buildListQuery(IResource resource, Set<RoleResource> roles)
    {
        StringBuilder builder =
                new StringBuilder("SELECT " + ROLE + ", "
                                            + RESOURCE + ", "
                                            + PERMISSIONS + ", "
                                            + RESTRICTED + ", "
                                            + GRANTABLES
                                            + " FROM " +  ROLE_PERMISSIONS_TABLE);

        boolean hasResource = resource != null;
        boolean hasRoles = roles != null && !roles.isEmpty();

        if (hasResource)
        {
            builder.append(" WHERE resource = '")
                   .append(escape(resource.getName()))
                   .append('\'');
        }
        if (hasRoles)
        {
            builder.append(hasResource ? " AND " : " WHERE ")
                   .append(ROLE + " IN ")
                   .append(roles.stream()
                           .map(r -> escape(r.getRoleName()))
                           .collect(Collectors.joining("', '", "('", "')")));
        }
        builder.append(" ALLOW FILTERING");
        return builder.toString();
    }

    public Set<DataResource> protectedResources()
    {
        return ImmutableSet.of(DataResource.table(SchemaConstants.AUTH_KEYSPACE_NAME, AuthKeyspace.ROLE_PERMISSIONS));
    }

    public void validateConfiguration() throws ConfigurationException
    {
    }

    public void setup()
    {
        authorizeRoleStatement = getStatement("SELECT %s, %s, %s FROM %s.%s WHERE resource = ? AND %s IN ?",
                                              PERMISSIONS,
                                              RESTRICTED,
                                              GRANTABLES,
                                              SchemaConstants.AUTH_KEYSPACE_NAME,
                                              AuthKeyspace.ROLE_PERMISSIONS,
                                              ROLE);
    }

    @SuppressWarnings("unchecked")
    private final <T extends CQLStatement> T getStatement(String query, Object... arguments)
    {
        String cql = String.format(query, arguments);
        return (T) QueryProcessor.getStatement(cql, ClientState.forInternalCalls()).statement;
    }

    // We only worry about one character ('). Make sure it's properly escaped.
    private static String escape(String name)
    {
        return StringUtils.replace(name, "'", "''");
    }

    private static UntypedResultSet process(String query, Object... arguments) throws RequestExecutionException
    {
        String cql = String.format(query, arguments);
        logger.info("EXECUTING {}", cql);
        return QueryProcessor.processBlocking(cql, ConsistencyLevel.LOCAL_ONE);
    }
}
