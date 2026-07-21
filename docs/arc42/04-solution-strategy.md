[← back to index](README.md)

# 4. Solution Strategy

Five decisions carry the whole architecture; each is expanded into a full
ADR in [Architecture Decisions](09-architecture-decisions.md).

1. **Bundle the engine inside the EAR and bootstrap it declaratively**
   (`camunda.cfg.xml`) **plus a small CDI bean**, instead of installing the
   Camunda WildFly Subsystem. This is the foundational choice the whole
   project is built to demonstrate - see [Introduction and Goals](01-introduction-and-goals.md).

2. **Route Job Executor execution through JBoss's own `ManagedExecutorService`**
   via a ~40-line custom `JobExecutor` (`ManagedJobExecutor`), instead of
   letting Camunda's default `ThreadPoolJobExecutor` create and manage its
   own `java.util.concurrent.ThreadPoolExecutor`. This is the single most
   load-bearing piece of custom code in the project - it's the concrete
   answer to "is the thread pool actually container-managed."

3. **Decouple the process application from the engine entirely.**
   `process-application` has no compile-time dependency on `camunda-engine`
   and cannot see its classes at runtime (`ear-subdeployments-isolated=true`).
   It deploys its BPMN process via the engine's own REST API at startup and
   handles its one task via the External Task REST API - the same
   integration surface any external system would use.

4. **Keep the datasource on the server, not in the EAR.** Everything else
   about "container integration" is inside the EAR by design; the
   datasource is the one deliberate exception, because the alternative (a
   deployable `*-ds.xml` bundling its own driver) depends on an
   undocumented, version-sensitive JBoss auto-naming convention - a worse
   trade than a one-time server-side CLI script.

5. **Verify every non-trivial claim against a real, running container**
   rather than trusting documentation or research. This produced a
   Testcontainers-based integration test suite (`integration-test/`) and,
   in the process, surfaced two real bugs that looked correct on paper
   (a nonexistent listener class; a silent JSON-B/Jackson provider
   conflict) - see [Risks and Technical Debt](11-risks-and-technical-debt.md).
   This isn't incidental test coverage; it's a load-bearing part of how
   this project arrives at trustworthy conclusions at all.
