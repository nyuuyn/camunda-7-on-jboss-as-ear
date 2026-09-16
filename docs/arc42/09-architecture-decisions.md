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

### ADR-4: Datasource and driver bundled in the EAR (superseded original decision)

*Original decision (2026), superseded by Addendum 2 below - kept for
history. Current state: the datasource and its driver both live in the
EAR; there is no server-side setup at all.*

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

**Addendum 1: the datasource *declaration* moved back into the EAR.** The
`*-ds.xml`/driver-jar-bundling problem above is specifically about
auto-detecting a driver from a jar embedded in the deployment - it doesn't
apply to declaring the *pool* itself via the JEE-standard
`@DataSourceDefinition` annotation. `CamundaEngineBootstrap` now carries
`@DataSourceDefinition` for `java:app/datasources/ProcessEngine`.

Two non-obvious things were found by deploying and reading the resulting
errors, not from documentation:

- **`@DataSourceDefinition`'s `className` is resolved differently from a
  CLI-created datasource's `driver-class-name`.** A CLI `data-source add`
  matches its driver by name against the datasources subsystem's
  `jdbc-driver` registry. `@DataSourceDefinition` instead loads `className`
  directly through the *deployment's own* module classloader
  (`org.jboss.as.connector.deployers.datasource.DataSourceDefinitionInjectionSource`)
  - deploying without the driver class visible anywhere threw
  `ClassNotFoundException: org.h2.Driver`.
- **`@DataSourceDefinition.className` must name a
  `javax.sql.DataSource`/`XADataSource`/`ConnectionPoolDataSource`
  implementation, not a `java.sql.Driver`.** `org.h2.Driver` (the class
  the CLI/`*-ds.xml` world wants) fails deployment with `WFLYJCA0117:
  ... is not a valid javax.sql.DataSource implementation`; H2's actual
  `DataSource` implementation is `org.h2.jdbcx.JdbcDataSource`.

**Addendum 2: the driver moved into the EAR too - server-side setup is
now zero, by default.** Addendum 1 first solved the `className`
classloading problem the same way the original CLI approach did (install
`com.h2database.h2` as a server module, then add an explicit `<module>`
dependency for `camunda-engine.war` in `jboss-deployment-structure.xml` so
the deployment's classloader could see it). But that's solving the wrong
problem for a driver like H2 that's perfectly redistributable: since
`@DataSourceDefinition` just needs `className` loadable through the
deployment's *own* classloader, bundling the driver jar directly in the
WAR satisfies that with no server module involved at all.
`camunda-engine/pom.xml` now ships `com.h2database:h2` at `runtime` scope
(previously `test`-only), so `org.h2.jdbcx.JdbcDataSource` lands straight
in `camunda-engine.war`'s own `WEB-INF/lib`. The `<module
name="com.h2database.h2"/>` dependency in `jboss-deployment-structure.xml`
was removed - confirmed unnecessary by deploying to a completely stock
WildFly with no server-side setup at all and getting a clean boot.

This doesn't retroactively make the original decision wrong: bundling a
driver *jar* as an auto-detected `*-ds.xml` driver (the thing ADR-4
rejected) and bundling one as a plain dependency a portable
`@DataSourceDefinition`-declared pool loads via ordinary classloading are
different mechanisms with different failure modes - only the former
depends on JBoss's undocumented auto-generated driver-name convention.
There's no `server-config/` directory or CLI script in this project
anymore; if a real deployment ever needs a driver installed as a server
module instead of bundled (e.g. a licensed production driver that can't be
redistributed in build artifacts), that means reintroducing both a
`module add`/`jdbc-driver=...:add` CLI step and an explicit `<module>`
dependency for `camunda-engine.war` in `jboss-deployment-structure.xml` -
the same shape Addendum 1 used before this addendum removed it.

A third finding, specific to the Testcontainers test image, came out of
verifying this: a writable `WORKDIR` is needed for `@DataSourceDefinition`'s
relative H2 file URL to resolve (the JVM's `user.dir` is wherever
`standalone.sh` was launched from) - see
[Deployment View §7.2](07-deployment-view.md).

See [Deployment View §7.1-7.2](07-deployment-view.md) and
[Crosscutting Concepts §8.2](08-crosscutting-concepts.md) for the full
picture.

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
against WildFly 26.1.3.Final - EAP 7.4's freely available upstream, same
`javax.*` codebase generation - with `$JBOSS_HOME` copied from quay.io's
jdk11-tagged image onto a JDK 17 base (quay.io never published a
`26.1.3.Final-jdk17` tag; its jdk17 tags only start at WildFly
28/Jakarta EE 10, outside this project's target). The base image is a
Dockerfile `ARG`, swappable for a real EAP image by anyone with access.

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

---

### ADR-9: Cockpit/Tasklist/Admin as a separate WAR module, sharing the engine via `ear/lib/`

**Context.** Cockpit, Tasklist, and Admin weren't deployed at all
originally (REST-API-only scope). Adding them raises a real question this
project's other modules don't: they need direct Java-API access to the
running `ProcessEngine` (`ProcessEngines.getProcessEngines()`), not just
REST - so the `process-application` pattern (ADR-3, zero compile-time
dependency, REST-only) doesn't apply to them the way it might seem to.

**Decision.** A new `camunda-web-ui` module (`war` packaging), built by
overlaying Camunda's prebuilt `org.camunda.bpm.webapp:camunda-webapp` WAR
with no customization - deployed as its own EAR subdeployment, same as
`camunda-engine.war` and `process-application.jar`.
`ear-subdeployments-isolated=true` still applies, but that only blocks
subdeployments from seeing *each other's own* classes; `ear/lib/camunda-engine.jar`
stays visible to every subdeployment regardless, and `camunda-webapp`'s
own `WEB-INF/lib` deliberately doesn't bundle a second copy of
`camunda-engine.jar` (confirmed by inspecting the published artifact
directly). So `camunda-web-ui.war`'s `ProcessEnginesFilter` resolves
against the exact same shared `ProcessEngines` class, and therefore the
same registered engine, that `CamundaEngineBootstrap` in
`camunda-engine.war` initialized - without any explicit
`jboss-deployment-structure.xml` module dependency between the two WARs.

**Consequences.** `camunda-web-ui` needs the same
`org.jboss.resteasy.resteasy-json-binding-provider` exclusion as
`camunda-engine.war` (it embeds its own RESTEasy + Jackson JAX-RS servlets
the same way). Verified with a real login against the seeded `admin` user
through the webapp's own CSRF-protected endpoint
(`integration-test`'s `webUiLoginWorksAgainstSharedEngine`), not just "the
WAR deployed without errors" - see
[Crosscutting Concepts §8.5](08-crosscutting-concepts.md) and
[Building Block View §5.4](05-building-block-view.md).
