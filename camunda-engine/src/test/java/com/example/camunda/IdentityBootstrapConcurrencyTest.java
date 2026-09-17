package com.example.camunda;

import org.camunda.bpm.engine.AuthorizationService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.authorization.Groups;
import org.camunda.bpm.engine.authorization.Resources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the domain-controller concurrent-bootstrap race - see
 * docs/arc42/08-crosscutting-concepts.md §8.7. Several threads racing
 * IdentityBootstrap.run() against one shared in-memory H2 database stand in
 * for several nodes racing it against one real database.
 */
class IdentityBootstrapConcurrencyTest {

    private static final int CONCURRENT_NODES = 8;

    private ProcessEngine engine;

    @BeforeEach
    void buildEngine() {
        ProcessEngineConfiguration configuration = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
        // Unique per test run so RepeatedTest invocations (and any other
        // test class using the same helper) don't share a schema.
        configuration.setJdbcUrl("jdbc:h2:mem:identity-bootstrap-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        engine = configuration.buildProcessEngine();
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @RepeatedTest(5)
    void concurrentBootstrapFromMultipleNodesCreatesEachRowExactlyOnce() throws Exception {
        ExecutorService nodes = Executors.newFixedThreadPool(CONCURRENT_NODES);
        CountDownLatch allNodesReady = new CountDownLatch(CONCURRENT_NODES);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger failureCount = new AtomicInteger();

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_NODES; i++) {
            futures.add(nodes.submit(() -> {
                allNodesReady.countDown();
                try {
                    // Line every simulated node up so they hit
                    // IdentityBootstrap.run() at the same instant, the way
                    // several domain nodes deploying in parallel would.
                    startSignal.await();
                    new IdentityBootstrap().run(engine);
                } catch (Throwable t) {
                    failureCount.incrementAndGet();
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            }));
        }

        allNodesReady.await(10, TimeUnit.SECONDS);
        startSignal.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        nodes.shutdown();

        assertTrue(failures.isEmpty(),
                "IdentityBootstrap.run() should tolerate concurrent creation from other nodes, but " + failureCount
                        + " of " + CONCURRENT_NODES + " concurrent runs threw: " + failures);

        IdentityService identityService = engine.getIdentityService();
        AuthorizationService authorizationService = engine.getAuthorizationService();

        assertEquals(1, identityService.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count());
        assertEquals(1, identityService.createGroupQuery().groupId(IdentityBootstrap.GROUP_SUPPORT).count());
        assertEquals(1, identityService.createGroupQuery().groupId(IdentityBootstrap.GROUP_READONLY).count());

        assertEquals(1, identityService.createUserQuery().userId(IdentityBootstrap.USER_ADMIN).count());
        assertEquals(1, identityService.createUserQuery().userId(IdentityBootstrap.USER_SUPPORT).count());
        assertEquals(1, identityService.createUserQuery().userId(IdentityBootstrap.USER_READONLY).count());

        assertEquals(1, identityService.createGroupQuery()
                .groupId(Groups.CAMUNDA_ADMIN).groupMember(IdentityBootstrap.USER_ADMIN).count());
        assertEquals(1, identityService.createGroupQuery()
                .groupId(IdentityBootstrap.GROUP_SUPPORT).groupMember(IdentityBootstrap.USER_SUPPORT).count());
        assertEquals(1, identityService.createGroupQuery()
                .groupId(IdentityBootstrap.GROUP_READONLY).groupMember(IdentityBootstrap.USER_READONLY).count());

        assertEquals(1, authorizationService.createAuthorizationQuery()
                .groupIdIn(Groups.CAMUNDA_ADMIN).resourceType(Resources.APPLICATION).count());
        assertEquals(1, authorizationService.createAuthorizationQuery()
                .groupIdIn(IdentityBootstrap.GROUP_SUPPORT).resourceType(Resources.PROCESS_INSTANCE).count());
        assertEquals(2, authorizationService.createAuthorizationQuery()
                .groupIdIn(IdentityBootstrap.GROUP_READONLY).count());
    }
}
