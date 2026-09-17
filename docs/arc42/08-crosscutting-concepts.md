[← back to index](README.md)

# 8. Crosscutting Concepts

## 8.1 Thread and Concurrency Management

This is the project's central concept, so it gets stated precisely:

**What *is* container-managed:** the Job Executor's *execution* thread pool.
`ManagedJobExecutor` (pure JEE - `javax.naming` + `java.util.concurrent`,
no Spring types) looks up `java:jboss/ee/concurrency/executor/default` via
JNDI and submits job execution `Runnable`s to it, instead of letting
Camunda's default `ThreadPoolJobExecutor` construct its own
`java.util.concurrent.ThreadPoolExecutor`. This is verified, not just
implemented - see [integration tests](../../integration-test) and
[Runtime View §6.2](06-runtime-view.md).

**What is *not* container-managed, deliberately, and why that's an
accepted exception rather than a gap:**

| Thread | What it does | Why it stays self-managed |
|---|---|---|
| Job-*acquisition* thread (inside `JobExecutor`) | Polls the database periodically for due jobs | A single, lightweight, long-lived polling loop - the same is true even under Camunda's *official* WildFly subsystem, which only containerizes the execution pool, not this loop. |
| `ExternalTaskClient`'s polling thread (`TopicSubscriptionManager`) | Long-polls `fetchAndLock` over REST | The client is designed to run standalone, potentially in a completely separate process from the engine - forcing it onto the app server's pool would fight its intended usage pattern for no real benefit, and it's the same class of "one lightweight loop" as the acquisition thread above. |
| `DemoProcessDeployer`'s retry loop | Retries the one-time REST deployment call at startup | *Is* routed through `ManagedExecutorService` (via `@Resource ManagedExecutorService`) - included here as a contrast: this one genuinely could and should be container-managed (it does real, possibly-slow HTTP work, unlike the two lightweight loops above), so it is. |

The dividing line isn't "is it a background thread" - it's "does it do
potentially-concurrent, potentially-slow *work*" (execution pool,
deployer's HTTP calls) versus "is it a single lightweight loop waiting on
something" (acquisition, external-task polling).

## 8.2 Transactions and Persistence

JTA and the datasource are both referenced by **plain JNDI name strings**,
not Java objects: `JtaProcessEngineConfiguration.setTransactionManagerJndiName("java:/TransactionManager")`
and `.setDataSourceJndiName("java:app/datasources/ProcessEngine")`. The
engine performs its own `InitialContext` lookups internally - this is why
neither of these needs Spring's `jee:jndi-lookup` namespace or any custom
code; they're just configuration values.

The datasource behind that second name is itself declared inside the EAR,
via `@DataSourceDefinition` on `CamundaEngineBootstrap` - not a
server-side `data-source add` - which is why the name lives under
`java:app/` rather than the JBoss-proprietary `java:jboss/` the CLI
approach used: `@DataSourceDefinition`'s `name` must be one of the four
EE-standard JNDI namespaces (`comp`/`module`/`app`/`global`).
`java:app` was chosen for EAR-wide visibility, matching `java:jboss/`'s
old effective scope. `@DataSourceDefinition` resolves its `className` by
loading it through the deployment's own module classloader, not the
datasources subsystem's driver registry - which is exactly why
`camunda-engine/pom.xml` bundles `com.h2database:h2` at `runtime` scope
into `camunda-engine.war`'s own `WEB-INF/lib`, making the whole thing
self-contained with zero server-side setup. See
[ADR-4](09-architecture-decisions.md) and
[Deployment View §7.1](07-deployment-view.md) for the full history.

## 8.3 Engine Bootstrap Format

`camunda.cfg.xml` is plain `spring-beans.xsd` `<bean>`/`<property>` XML -
that's the format `camunda-engine`'s built-in classpath-scan bootstrap
parses internally, *regardless* of whether the `camunda-engine-spring`
module is on the classpath. No `org.springframework.*` type appears
anywhere in this project's own Java code, and `camunda-engine-spring` is
not a dependency - see [ADR: no Spring dependency](09-architecture-decisions.md).

## 8.4 REST API Embedding

`CamundaRestApplication` (a plain `javax.ws.rs.core.Application`) embeds
`camunda-engine-rest-core`'s resource classes directly into
`camunda-engine`'s own JAX-RS deployment (RESTEasy, built into JBoss EAP),
rather than deploying Camunda's separate `engine-rest.war`. Which
`ProcessEngine` the REST resources operate on is resolved by
`ContainerManagedProcessEngineProvider`, which falls back to the plain
`ProcessEngines` registry when there's no subsystem/`BpmPlatform` involved
- exactly this project's situation.

Two non-obvious pieces are required for this to actually work (both found
by deploying and reading the resulting errors, not documented anywhere
found during research - see [Risks and Technical Debt](11-risks-and-technical-debt.md)):
`FetchAndLockContextListener` (required by External Task long-polling) and
excluding WildFly's built-in JSON-B provider module (which otherwise wins
over Jackson for `application/json` and breaks date parsing).

## 8.5 Decoupling via REST-Only Communication

`process-application` has zero compile-time dependency on `camunda-engine`
and cannot see its classes at runtime: `ear-subdeployments-isolated=true`
in `ear/META-INF/jboss-deployment-structure.xml` enforces this at the
classloader level, not just by convention. It talks to the engine only via
the deployment REST API (`DemoProcessDeployer`) and the External Task REST
API (`DemoExternalTaskWorker`) - the same integration surface any external
system would use. `ear/lib/` (the shared engine/REST/client jars) stays
visible to both subdeployments regardless of the isolation setting; only
cross-subdeployment class visibility is affected.

**`camunda-web-ui` is a deliberate exception to this pattern, not a
violation of it.** Cockpit/Tasklist/Admin need direct Java-API access to
the running `ProcessEngine` (`ProcessEngines.getProcessEngines()`) - a
REST-only integration isn't an option for them the way it is for a process
application. It's still its own genuinely separate EAR subdeployment
(same isolation setting applies), but it resolves the engine through
`ear/lib/camunda-engine.jar` - the same shared-library visibility
`process-application` deliberately doesn't rely on - rather than a REST
call. See [Building Block View §5.4](05-building-block-view.md) for how
that resolves without any explicit module dependency between the two WARs.

## 8.6 Testing Philosophy: Verify, Don't Assume

Documentation and web research about how Camunda 7 interacts with plain
JEE containers turned out to be wrong twice during this project's
development (see [Risks and Technical Debt](11-risks-and-technical-debt.md)) -
in both cases, the code compiled and packaged cleanly, and only failed once
actually deployed to a real container. The response was to build a
Testcontainers-based integration suite (`integration-test/`) that treats
"looks correct" and "verified against a running deployment" as different
things, and to keep tightening assertions when a check turns out to prove
less than it looks like it proves (e.g. correlating a specific job id with
a specific thread-name log line, rather than checking each fact in
isolation and assuming they're related).

## 8.7 Concurrent Identity Bootstrap Across Domain Nodes

`IdentityBootstrap` seeds example users/groups/authorizations the first
time the engine boots. In a JBoss domain-controller environment, the
Domain Controller pushes the EAR to every node in a server group in
parallel, so several nodes can run this bootstrap at roughly the same
time, each in its own JVM/transaction, with no lock between them. A plain
"check if it exists, then create it" isn't atomic under that: two nodes
can both see "not found" and both insert, and the loser's insert fails
against the database's own unique constraint (`ACT_ID_USER`/`ACT_ID_GROUP`
primary keys, the unique index backing `ACT_RU_AUTHORIZATION`).

Rather than trying to prevent the race, every create in `IdentityBootstrap`
treats a unique-constraint violation as "another node already created this
concurrently" and swallows it, instead of failing deployment. Detection
walks the exception's cause chain and asks the engine's own
`ExceptionUtil.checkConstraintViolationException` at each
`ProcessEngineException` - the same check `IdentityService`'s
`saveUser`/`saveGroup` use internally, so it stays correct across the DB
vendors this project might run against (H2, PostgreSQL, MySQL, ...) and
also catches `createMembership`/`saveAuthorization`, which have no such
wrapping of their own.

`IdentityBootstrap` is a `@Dependent` CDI bean rather than a static
utility because `run()` is `@Transactional`, and that annotation only does
anything when the container can intercept the call - which requires a
non-final, non-static method on a proxyable bean, invoked through an
injected reference (see `CamundaEngineBootstrap`'s `@Inject` call site),
not a direct static call.

`IdentityBootstrapConcurrencyTest` is the regression test: several threads
racing `IdentityBootstrap.run()` against one shared in-memory H2 database
reproduce the part of the bug that actually matters (concurrent
check-then-insert against unique-constrained rows), even though a single
JVM can't reproduce "several JVMs" itself.
