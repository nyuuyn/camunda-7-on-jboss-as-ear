[← back to index](README.md)

# 9. Architecture Decisions

Recorded as short ADRs. All are **Accepted** and currently in effect.

---

### ADR-1: Do not use the Camunda WildFly Subsystem

**Context.** The subsystem is Camunda's officially supported JBoss
integration, and would trivially satisfy "container-managed thread pool."

**Decision.** Don't install it. Bundle the engine inside the EAR instead.

**Consequences.** Every piece of container integration (bootstrap, thread
pool, transactions, datasource, REST) has to be assembled by hand - this
is the whole point of the project (see [Introduction and Goals](01-introduction-and-goals.md)),
not a limitation to work around.

---

### ADR-2: Custom `JobExecutor` delegating to JEE `ManagedExecutorService`

**Context.** Without the subsystem, Camunda's default `ThreadPoolJobExecutor`
would create and manage its own `java.util.concurrent.ThreadPoolExecutor` -
exactly the kind of self-managed thread pool the Java EE spec asks
applications not to create.

**Decision.** `ManagedJobExecutor` looks up `java:jboss/ee/concurrency/executor/default`
via JNDI and submits job execution there. Pure JEE (`javax.naming` +
`java.util.concurrent`), no Spring types.

**Consequences.** The job-*acquisition* polling thread stays self-managed
(a single lightweight loop, same as under the official subsystem - see
[Crosscutting Concepts §8.1](08-crosscutting-concepts.md)). This is the
project's most load-bearing piece of custom code and the one most
thoroughly covered by [integration tests](../../integration-test).

---

### ADR-3: Decouple `process-application` from `camunda-engine` via REST only

**Context.** Initially the process application and the engine bootstrap
lived in the same module (`process-archive`), coupled via `EjbProcessApplication`/
`processes.xml`. That's the conventional Camunda pattern, but it means the
process application shares a classloader and JVM-internal API surface with
the engine.

**Decision.** Split into two independent subdeployments. `process-application`
deploys its BPMN via the engine's own REST API at startup
(`DemoProcessDeployer`) and handles its task via the External Task REST
API (`DemoExternalTaskWorker`) - no compile-time dependency on
`camunda-engine`, enforced at runtime via `ear-subdeployments-isolated=true`.

**Consequences.** Two independently-initializing subdeployments means no
ordering guarantee between them - `DemoProcessDeployer` needs a
retry-with-backoff loop (see [Runtime View §6.1](06-runtime-view.md)).
In exchange, the process application is genuinely replaceable/testable
independently of the engine, and demonstrates the same integration surface
a real external system would use.

---

### ADR-4: Datasource configured on the server, not bundled in the EAR

**Context.** Everything else about container integration lives inside the
EAR by design. A datasource *can* be bundled too, as a deployable
`*-ds.xml` alongside a JDBC4 driver jar (auto-detected via
`META-INF/services/java.sql.Driver`) - this project did that at one point.

**Decision.** Move the datasource to a one-time server-side CLI script
(`server-config/add-datasource.cli`) instead.

**Consequences.** Bundling depends on JBoss's auto-generated driver-name
convention for a driver jar embedded in a deployment
(`<deployment-filename>_<driver-class>_<version>`) - undocumented enough,
and version-sensitive enough, to be a worse trade than registering the
datasource once via CLI. It also matches how a real datasource would be
managed operationally anyway (connection pool tuning, credentials, and
swapping in a real XA database are server/ops concerns, not something to
tie to an application's deployment lifecycle).

---

### ADR-5: No Spring dependency; plain `camunda.cfg.xml` bootstrap

**Context.** An earlier iteration used a hand-written `@Singleton @Startup`
EJB to build `JtaProcessEngineConfiguration` programmatically. Camunda also
ships a Spring integration module (`camunda-engine-spring`,
`SpringJobExecutor`) that would let a `TaskExecutor`-wrapping job executor
be declared without any custom Java code at all.

**Decision.** Use neither. `camunda.cfg.xml` (plain `spring-beans.xsd`
XML - the format `camunda-engine`'s built-in classpath-scan bootstrap
parses internally regardless of whether `camunda-engine-spring` is
present) declares the engine and job executor beans declaratively, with
`ManagedJobExecutor` (pure JEE) as the one hand-written class.

**Consequences.** No `org.springframework.*` type appears anywhere in this
project's own code, and no Spring dependency is added, at the cost of one
small custom `JobExecutor` class that Camunda's Spring module would have
provided for free.

---

### ADR-6: CDI-based engine bootstrap instead of a `ServletContextListener`

**Context.** `ProcessEngines.init()`/`.destroy()` need to run once, eagerly,
at webapp start/stop. A `ServletContextListener` (`@WebListener`)
guarantees this trivially. A plain `@ApplicationScoped` CDI bean does
*not* - CDI beans are lazy by default.

**Decision.** `CamundaEngineBootstrap`, an `@ApplicationScoped` bean, uses
`@Observes @Initialized(ApplicationScoped.class)`/`@Destroyed` to force
eager instantiation at the same lifecycle point a `ServletContextListener`
would fire. It also `@Produces` the `ProcessEngine` as an injectable CDI
bean.

**Consequences.** Requires `WEB-INF/beans.xml` to make the WAR a
recognized CDI bean archive (without it, the bean is never discovered).
In exchange, future CDI beans added to `camunda-engine` can `@Inject
ProcessEngine` directly instead of calling `ProcessEngines.getDefaultProcessEngine()`
themselves - the original motivation for making this change.

---

### ADR-7: WildFly as a stand-in for JBoss EAP in tests

**Context.** Real EAP images require `registry.redhat.io` + a Red Hat
subscription; this project needs to be buildable and testable by anyone.

**Decision.** [Integration tests](../../integration-test) and CI run
against `quay.io/wildfly/wildfly:26.1.3.Final-jdk11` - EAP 7.4's freely
available upstream, same `javax.*` codebase generation. The base image is
a Dockerfile `ARG`, swappable for a real EAP image by anyone with access.

**Consequences.** See [Risks and Technical Debt](11-risks-and-technical-debt.md) -
this is real, un-eliminated risk: WildFly and EAP are close but not
identical, and nothing here has been verified against real EAP.

---

### ADR-8: In-EAR REST embedding instead of Camunda's separate `engine-rest.war`

**Context.** Camunda ships a prebuilt `engine-rest.war` that could be
deployed alongside the EAR as a separate artifact.

**Decision.** Embed `camunda-engine-rest-core`'s resource classes directly
into `camunda-engine`'s own JAX-RS `Application` (`CamundaRestApplication`)
instead.

**Consequences.** One fewer artifact to deploy and version alongside the
EAR, at the cost of needing to get provider/listener wiring right by hand
(`ProcessEngineProvider` SPI, `FetchAndLockContextListener`, the JSON-B
provider exclusion) - all three were found by deploying and reading the
resulting errors, not by reading documentation. See
[Crosscutting Concepts §8.4](08-crosscutting-concepts.md).
