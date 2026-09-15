package com.example.camunda;

import org.camunda.bpm.engine.AuthorizationService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.authorization.Authorization;
import org.camunda.bpm.engine.authorization.Groups;
import org.camunda.bpm.engine.authorization.Permission;
import org.camunda.bpm.engine.authorization.Permissions;
import org.camunda.bpm.engine.authorization.ProcessInstancePermissions;
import org.camunda.bpm.engine.authorization.Resource;
import org.camunda.bpm.engine.authorization.Resources;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.impl.util.ExceptionUtil;

import javax.enterprise.context.Dependent;
import javax.transaction.Transactional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates a handful of example users/groups/authorizations the first time
 * the engine boots (see issue #1), via IdentityService/AuthorizationService
 * the way a real deployment would.
 *
 * In a JBoss 7.4 domain-controller environment, the Domain Controller pushes
 * this EAR to every node in a server group in parallel, so several nodes run
 * this exact bootstrap at roughly the same time, each in its own JVM/
 * transaction, with no lock between them. A plain "check if it exists, then
 * create it" is therefore not atomic: two nodes can both see "not found" and
 * both insert, and the loser's insert fails against the database's own
 * unique constraint (ACT_ID_USER/ACT_ID_GROUP primary keys, the unique index
 * backing ACT_RU_AUTHORIZATION) - the "Authorization must be unique"
 * exception from issue #1. There is no in-process lock that reaches across
 * nodes, so rather than trying to prevent that race, every create below
 * treats a unique-constraint violation as "another node already created
 * this concurrently, nothing left to do" instead of a fatal deployment
 * error - see isConcurrentCreateConflict below for how that's detected.
 *
 * <p>A {@code @Dependent} CDI bean rather than a static utility: run() is
 * {@code @Transactional}, and that annotation only does anything when the
 * container can intercept the call - which requires a non-final, non-static
 * method on an actual (proxyable) bean, invoked through an injected
 * reference rather than a direct static call. See CamundaEngineBootstrap for
 * the {@code @Inject} call site.
 */
@Dependent
class IdentityBootstrap {

    private static final Logger LOGGER = Logger.getLogger(IdentityBootstrap.class.getName());

    static final String GROUP_SUPPORT = "support";
    static final String GROUP_READONLY = "readonly";

    static final String USER_ADMIN = "admin";
    static final String USER_SUPPORT = "support";
    static final String USER_READONLY = "readonly";

    @Transactional
    public void run(ProcessEngine engine) {
        IdentityService identityService = engine.getIdentityService();
        AuthorizationService authorizationService = engine.getAuthorizationService();

        createGroup(identityService, Groups.CAMUNDA_ADMIN, "Camunda Administrators");
        createGroup(identityService, GROUP_SUPPORT, "Support");
        createGroup(identityService, GROUP_READONLY, "Read-only");

        createUser(identityService, USER_ADMIN, "Admin", "admin@example.com", Groups.CAMUNDA_ADMIN);
        createUser(identityService, USER_SUPPORT, "Support", "support@example.com", GROUP_SUPPORT);
        createUser(identityService, USER_READONLY, "Read Only", "readonly@example.com", GROUP_READONLY);

        // camunda-admin already bypasses authorization checks entirely (see
        // DbAuthorizationManager#isCamundaAdmin) - this grant only makes the
        // admin webapps (Cockpit/Tasklist/Admin) list themselves as
        // accessible, same as Camunda's own "create initial admin" wizard.
        grantGroupAuthorization(authorizationService, Groups.CAMUNDA_ADMIN, Resources.APPLICATION, Permissions.ALL);
        grantGroupAuthorization(authorizationService, GROUP_SUPPORT, Resources.PROCESS_INSTANCE,
                Permissions.READ, ProcessInstancePermissions.RETRY_JOB);
        grantGroupAuthorization(authorizationService, GROUP_READONLY, Resources.PROCESS_DEFINITION, Permissions.READ);
        grantGroupAuthorization(authorizationService, GROUP_READONLY, Resources.PROCESS_INSTANCE, Permissions.READ);
    }

    private static void createGroup(IdentityService identityService, String groupId, String groupName) {
        ignoringConcurrentCreate(() -> {
            if (identityService.createGroupQuery().groupId(groupId).count() == 0) {
                Group group = identityService.newGroup(groupId);
                group.setName(groupName);
                group.setType(Groups.GROUP_TYPE_SYSTEM);
                identityService.saveGroup(group);
            }
        });
    }

    private static void createUser(IdentityService identityService, String userId, String firstName,
                                    String email, String groupId) {
        ignoringConcurrentCreate(() -> {
            if (identityService.createUserQuery().userId(userId).count() == 0) {
                User user = identityService.newUser(userId);
                user.setFirstName(firstName);
                user.setEmail(email);
                user.setPassword(userId);
                identityService.saveUser(user);
            }
        });
        ignoringConcurrentCreate(() -> {
            if (identityService.createGroupQuery().groupId(groupId).groupMember(userId).count() == 0) {
                identityService.createMembership(userId, groupId);
            }
        });
    }

    private static void grantGroupAuthorization(AuthorizationService authorizationService, String groupId,
                                                 Resource resource, Permission... permissions) {
        ignoringConcurrentCreate(() -> {
            boolean exists = authorizationService.createAuthorizationQuery()
                    .groupIdIn(groupId)
                    .resourceType(resource)
                    .resourceId(Authorization.ANY)
                    .count() > 0;
            if (!exists) {
                Authorization authorization = authorizationService.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
                authorization.setGroupId(groupId);
                authorization.setResource(resource);
                authorization.setResourceId(Authorization.ANY);
                for (Permission permission : permissions) {
                    authorization.addPermission(permission);
                }
                authorizationService.saveAuthorization(authorization);
            }
        });
    }

    private static void ignoringConcurrentCreate(Runnable create) {
        try {
            create.run();
        } catch (ProcessEngineException e) {
            if (!isConcurrentCreateConflict(e)) {
                throw e;
            }
            LOGGER.log(Level.FINE,
                    "Lost the race to create identity/authorization data to another concurrently-starting "
                            + "node - it already exists, ignoring.", e);
        }
    }

    /**
     * IdentityService's own saveGroup/saveUser/saveTenant already probe for
     * exactly this (see their use of the same ExceptionUtil method) and
     * rethrow as BadUserRequestException("... already exists", cause) -
     * catching just that message would miss createMembership/
     * saveAuthorization, which have no such wrapping and let the underlying
     * ProcessEngineException (wrapping the DB's unique-constraint violation)
     * propagate as-is. Walking the cause chain and asking the engine's own
     * ExceptionUtil at each ProcessEngineException handles both shapes
     * uniformly, and stays correct across the DB vendors this project might
     * run against (H2, PostgreSQL, MySQL, ...) since it's the same check the
     * engine itself relies on.
     */
    private static boolean isConcurrentCreateConflict(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProcessEngineException
                    && ExceptionUtil.checkConstraintViolationException((ProcessEngineException) cause)) {
                return true;
            }
        }
        return false;
    }
}
