[← back to index](README.md)

# 11. Risks and Technical Debt

## 11.1 Bugs Already Found (and Fixed) by Actually Deploying

Both were invisible at compile/package time - the project built cleanly
both times - and only surfaced once the EAR was actually deployed to a
real container. Kept here as evidence for why [Crosscutting Concepts §8.6](08-crosscutting-concepts.md)
(verify, don't assume) is a real practice in this project, not a slogan:

1. **A nonexistent Camunda class.** The original engine bootstrap
   referenced `org.camunda.bpm.engine.test.impl.servlet.listener.ProcessEnginesServletContextListener`,
   a class name that came from unverified web research and simply doesn't
   exist in `camunda-engine:7.19.0`. Deployment failed with
   `ClassNotFoundException`. Fixed by writing `CamundaEngineBootstrap`
   against the real, jar-verified `ProcessEngines.init()`/`.destroy()` API
   (later evolved into a CDI bean - see [ADR-6](09-architecture-decisions.md)).
2. **A silent JSON-B/Jackson provider conflict.** WildFly's built-in
   JSON-B (Yasson) provider was winning over the bundled Jackson provider
   for `application/json` responses, serializing dates in a format
   (`...Z[UTC]`, JSON-B's default `ISO_ZONED_DATE_TIME`) that the External
   Task Client's own bundled (older) Jackson couldn't parse - every
   `fetchAndLock` response failed to deserialize. Fixed by excluding
   `org.jboss.resteasy.resteasy-json-binding-provider` for `camunda-engine.war`
   in `jboss-deployment-structure.xml`. Also needed:
   `FetchAndLockContextListener`, without which every `fetchAndLock` call
   NPE'd server-side - required by the External Task REST API but not
   mentioned anywhere found while researching the REST embedding setup.

## 11.2 Untested Against Real JBoss EAP

Every automated check in this project (CI, `mvn verify`) runs against
WildFly 26.1.3.Final, not real JBoss EAP 7.4 - see [ADR-7](09-architecture-decisions.md).
WildFly is EAP's upstream and shares the same `javax.*`-generation
codebase, but they are not identical:

- EAP includes Red Hat-specific patches, different default configuration,
  and a different (slower) release cadence.
- The exact JNDI name/behavior of `java:jboss/ee/concurrency/executor/default`
  is assumed identical between WildFly 26 and EAP 7.4 - plausible given the
  shared lineage, but not independently confirmed against real EAP.

**Mitigation available but not exercised:** the Dockerfile's `BASE_IMAGE`
build arg can be pointed at a real EAP image by anyone with
`registry.redhat.io` access - see [Deployment View §7.2](07-deployment-view.md).

## 11.3 Self-Managed Threads That Remain Self-Managed

Two thread types are deliberately *not* routed through JBoss's managed
executor - see [Crosscutting Concepts §8.1](08-crosscutting-concepts.md)
for the reasoning (they're single lightweight polling loops, the same
exception the official Camunda subsystem itself makes):

- The Job Executor's job-*acquisition* thread.
- `ExternalTaskClient`'s long-polling thread.

This is a documented, deliberate exception rather than a gap, but it does
mean the project's "container-managed threads" claim is scoped to
*execution*, not every background thread the engine or its client
libraries create.

## 11.4 Test Design Dead Ends (Kept as Institutional Memory)

Two approaches to verifying the thread-pool claim were tried and abandoned
during development - worth recording so they aren't re-attempted blind:

- **Reading a live JVM thread dump (`jcmd Thread.print`) via `docker exec`.**
  `jcmd` costs roughly 1 second per invocation (JVM attach overhead) -
  far too slow to reliably catch a job execution that completes in
  single-digit milliseconds, even under load (100 concurrent process
  starts still drained faster than the first sample could be taken).
- **Throttling the datasource pool to force a backlog.** Dropping
  `max-pool-size` to 1 to slow job execution down enough to sample
  initially looked like it worked, but the "hits" turned out to be a false
  positive: `DemoProcessDeployer` and `DemoExternalTaskWorker` live in the
  same Java package as `ManagedJobExecutor` and also submit work to the
  same shared pool, so a broad `com.example.camunda` string match caught
  *their* blocked threads, not genuine job execution. Tightening the check
  to `ManagedJobExecutor`/engine-specific frames produced zero real hits -
  the throttling mostly starved the 2-thread pool's availability rather
  than widening the execution window.

The approach that *does* work - correlating a specific job id (from the
History REST API) with a specific log line - is what's actually
implemented; see [Quality Requirements, scenario 1](10-quality-requirements.md).

## 11.5 Camunda 7's Maintenance Status

Camunda 7 Community Edition's active development has wound down (Camunda
7.24 is the final planned minor release; Camunda's own investment has
shifted to Camunda 8). This project depends on several Camunda 7 artifacts
(`camunda-engine`, `camunda-engine-rest-core`, `camunda-external-task-client`)
that will receive decreasing maintenance over time. Not a defect today, but
a real factor in how long this architecture stays this easy to build.

## 11.6 Demo-Grade Persistence

H2, in-memory, single connection pool, no HA - adequate for demonstrating
container integration, not representative of a production datasource. See
[System Scope §3.3](03-system-scope-and-context.md) for what's explicitly
out of scope.
