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
 * the engine boots, tolerating the concurrent-bootstrap race that occurs
 * when a domain controller starts this EAR on several nodes at once. See
 * docs/arc42/08-crosscutting-concepts.md §8.7 for the full rationale.
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

        // camunda-admin already bypasses authorization checks (DbAuthorizationManager#isCamundaAdmin);
        // this grant just makes the admin webapps list themselves as accessible.
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
     * Walks the cause chain rather than matching on exception type/message
     * so it catches createMembership/saveAuthorization too, not just the
     * saveUser/saveGroup calls IdentityService itself already probes for.
     * See docs/arc42/08-crosscutting-concepts.md §8.7.
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
