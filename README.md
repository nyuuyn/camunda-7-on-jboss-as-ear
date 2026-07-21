# Camunda 7 on JBoss EAP as a self-contained EAR

[![CI](https://github.com/nyuuyn/camunda-7-on-jboss-as-ear/actions/workflows/ci.yml/badge.svg)](https://github.com/nyuuyn/camunda-7-on-jboss-as-ear/actions/workflows/ci.yml)

Demonstrates deploying Camunda 7 as a Java EE EAR on JBoss EAP 7.4 with no
Camunda subsystem installed, and with the process application fully
decoupled from the engine: it never shares a classloader with it, only
talks to it over HTTP. The one thing kept on the server side, deliberately,
is the datasource — see
[Why the datasource lives on the server](#why-the-datasource-lives-on-the-server).

## Architecture

Two modules, deployed together in one EAR for convenience but logically
independent:

- **`camunda-engine`** (war) — the process engine and nothing else.
  - `camunda.cfg.xml` declares the `processEngineConfiguration` bean
    (`JtaProcessEngineConfiguration`) declaratively. `CamundaEngineBootstrap`,
    an `@ApplicationScoped` CDI bean, triggers `ProcessEngines.init()`/`.destroy()`
    via `@Observes @Initialized(ApplicationScoped.class)`/`@Destroyed` -
    plain `@ApplicationScoped` alone would *not* do this, since CDI beans
    are lazy by default; the observer methods are what force eager
    instantiation at webapp startup. It also exposes the engine as an
    injectable bean (`@Produces ProcessEngine`) for future CDI beans in
    this WAR. Requires `WEB-INF/beans.xml` to make the WAR a recognized
    bean archive. An earlier version of this bootstrap was a
    `ServletContextListener` referencing
    `org.camunda.bpm.engine.test.impl.servlet.listener.ProcessEnginesServletContextListener` -
    a class name that turned out not to exist anywhere in `camunda-engine:7.19.0`
    (an artifact of unverified web research; caught by actually deploying
    to a container - see [Integration testing](#integration-testing)).
  - **Transactions** — `transactionManagerJndiName` points at
    `java:/TransactionManager`; the engine does its own `InitialContext`
    lookup internally, so this is a plain JNDI-name string, no Spring or
    custom code involved.
  - **Database** — a datasource configured on the server via
    `server-config/add-datasource.cli`, looked up (again by JNDI name
    string) at `java:jboss/datasources/ProcessEngine`.
  - **Job Executor thread pool** — `ManagedJobExecutor` is a custom
    `JobExecutor` - pure JEE (`javax.naming` + `java.util.concurrent`, no
    Spring types) - that submits job execution to JBoss's *built-in*
    `ManagedExecutorService` (`java:jboss/ee/concurrency/executor/default`,
    present in EAP 7.4 out of the box) instead of the
    `java.util.concurrent.ThreadPoolExecutor` that Camunda's own
    `ThreadPoolJobExecutor` would create and manage itself. It looks the
    executor up itself via `init-method="init"` in `camunda.cfg.xml`, so
    the XML only ever passes it a JNDI name string, never a Java object.
    The one exception is the job-*acquisition* polling thread (the loop
    that queries for due jobs) — that stays a single dedicated background
    thread owned by the engine, same as under the official subsystem,
    which also doesn't containerize that particular thread.
  - **REST API** — `CamundaRestApplication` embeds `camunda-engine-rest-core`
    into this WAR's own JAX-RS deployment (RESTEasy, built into JBoss EAP)
    under `/engine-rest`. `ContainerManagedProcessEngineProvider` (declared
    via `META-INF/services/org.camunda.bpm.engine.rest.spi.ProcessEngineProvider`)
    resolves which engine the REST resources operate on by falling back to
    the plain `ProcessEngines` registry - exactly what `camunda.cfg.xml`
    populates - since there's no subsystem/`BpmPlatform` involved.
    `web.xml` also declares `org.camunda.bpm.engine.rest.impl.FetchAndLockContextListener` -
    required by the External Task API's long-polling `fetchAndLock`
    endpoint, otherwise every call throws a server-side NPE. And
    `ear/META-INF/jboss-deployment-structure.xml` excludes WildFly's
    built-in `org.jboss.resteasy.resteasy-json-binding-provider` module
    from this WAR: without that exclusion, WildFly's JSON-B (Yasson)
    provider silently wins over Jackson for `application/json` and
    serializes dates as `...Z[UTC]` (JSON-B's default `ISO_ZONED_DATE_TIME`
    format), which the External Task Client's own bundled (older) Jackson
    can't parse - every `fetchAndLock` response failed to deserialize.
    Neither of these two issues is mentioned anywhere I found while
    researching the REST embedding setup; both were found by actually
    deploying the EAR to a container and reading the resulting errors -
    see [Integration testing](#integration-testing).

- **`process-application`** (ejb) — the whole demo, in one module:
  - `demo-process.bpmn` plus `DemoProcessDeployer`, a `@Singleton @Startup`
    bean that `POST`s the BPMN to `camunda-engine`'s REST API
    (`/deployment/create`, multipart) once at startup. Runs on JBoss's
    `ManagedExecutorService` with a retry-with-backoff loop (up to 30
    attempts, 2s apart), since there's no ordering guarantee the REST API
    is already up when this singleton starts (see the ordering caveat
    below).
  - `DemoExternalTaskWorker`, the replacement for the old in-process
    `JavaDelegate`. Subscribes to topic `demo-topic` (matching the BPMN's
    `camunda:type="external" camunda:topic="demo-topic"` service task) via
    `camunda-external-task-client` - a self-contained REST client
    (fetch-and-lock long-polling, then complete/fail). Its polling loop is,
    by design, a single lightweight thread - the client is meant to be run
    standalone, even in a separate process from the engine - so it isn't
    routed through `ManagedExecutorService` the way the Job Executor's
    execution pool is; that was a real pool doing potentially many
    concurrent invocations, this is one polling loop, the same class of
    thing as the engine's own job-acquisition thread.
  - **No compile-time dependency on `camunda-engine` at all** - this
    module only ever talks to the engine over HTTP, which is what actually
    keeps it decoupled rather than just organizationally separate.

```
camunda-engine (war)         -- camunda.cfg.xml, ManagedJobExecutor,
                                 CamundaRestApplication (/engine-rest)
process-application (ejb)    -- demo-process.bpmn, DemoProcessDeployer
                                 (deploys via REST at startup),
                                 DemoExternalTaskWorker (topic "demo-topic")
ear                          -- bundles both + engine/REST/client jars
server-config/                -- one CLI script: the ProcessEngine datasource
```

`ear-subdeployments-isolated=true` (see `ear/META-INF/jboss-deployment-structure.xml`)
makes this decoupling real, not just aspirational: `process-application`
cannot see `camunda-engine`'s classes even though they ship in the same
EAR. `ear/lib/` (the engine jars, `camunda-engine-rest-core`,
`camunda-external-task-client`) stays visible to both regardless of that
setting - only cross-subdeployment class visibility is affected.

**Note on `camunda.cfg.xml`'s format**: it's plain `spring-beans.xsd`
`<bean>`/`<property>` XML - that's the format `camunda-engine`'s built-in
classpath-scan bootstrap parses internally regardless of whether the
`camunda-engine-spring` module is present. No `org.springframework.*` type
appears anywhere in this project's own Java code, and `camunda-engine-spring`
is not a dependency here.

**Ordering caveat**: nothing guarantees `camunda-engine`'s REST API is up
before `process-application`'s `DemoProcessDeployer` or
`DemoExternalTaskWorker` start trying to reach it - these are two
independently-initializing subdeployments now, on purpose.
`DemoProcessDeployer` handles this with retries; `ExternalTaskClient`'s
long-polling loop is naturally resilient to an initially-unreachable
server. Confirmed against a real container (see
[Integration testing](#integration-testing)): a handful of `HTTP 404`/connection
errors in the first couple of seconds while `camunda-engine.war` is still
starting, then both recover on their own - no manual intervention needed.

## Build

```sh
mvn clean package
```

Produces `ear/target/camunda-demo.ear`.

## Deploy

1. Run `server-config/add-datasource.cli` once (see
   `server-config/README.md`) to create the `ProcessEngine` datasource.
2. ```sh
   cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
   ```

## Verify the container integration

The fastest way to check all of this is `mvn verify` (see
[Integration testing](#integration-testing)) - it does exactly the steps
below, automatically, against a real container, every time. To do it by
hand against your own JBoss instance:

1. Tail `standalone/log/server.log` for `DemoProcessDeployer`'s "Deployed
   demo-process.bpmn via REST API" log line, confirming the REST-based
   deployment worked.
2. `curl -X POST http://localhost:8080/camunda-engine/engine-rest/process-definition/key/demoProcess/start -H "Content-Type: application/json" -d "{}"`
   — starts a process instance via the engine's own REST API.
3. `curl http://localhost:8080/camunda-engine/engine-rest/history/process-instance/{id}`
   — poll until `"state":"COMPLETED"`, confirming the async job ran *and*
   the external task was fetched, executed, and completed over REST.
4. Tail `server.log` for `ManagedJobExecutor`'s "Executing job(s) ... on
   thread [EE-ManagedExecutorService-default-Thread-N]" line, and
   `DemoExternalTaskWorker`'s "Handling external task ..." line - the
   former should show a JBoss-managed thread name, the latter its own
   dedicated `TopicSubscriptionManager` thread (see the Job Executor
   thread pool bullet under Architecture above for why that one isn't
   containerized).
5. `jboss-cli.sh --connect --command="/subsystem=ee/managed-executor-service=default:read-resource(include-runtime=true)"`
   — `completed-task-count` should have increased, confirming the pool the
   Job Executor's *execution* side runs on is a real JBoss-managed
   resource, not just a plausible-looking thread name.

## Integration testing

```sh
mvn verify
```

(Docker daemon required. `mvn clean package` never touches Docker - these
tests are opt-in, bound to `maven-failsafe-plugin`'s `integration-test`/`verify`
goals in the `integration-test` module, not `surefire`'s `test` goal.)

`CamundaEarIT` (`integration-test/src/test/java/`), via Testcontainers:

1. Builds a **WildFly 26.1.3.Final** image (`integration-test/src/test/resources/docker/Dockerfile`) -
   the freely-pullable stand-in for JBoss EAP 7.4 used throughout this
   project (real EAP images need a Red Hat subscription + `registry.redhat.io`
   login; `BASE_IMAGE` is a Dockerfile `ARG` if you have one). The
   `ProcessEngine` datasource is baked into the image at build time
   (`add-datasource.cli`, run via `embed-server`/`stop-embedded-server` -
   the offline-CLI variant of `server-config/add-datasource.cli`, reusing
   the `h2` JDBC driver WildFly already ships for its own `ExampleDS`
   rather than adding a second one, in-memory instead of file-based since
   containers are ephemeral). This layer is cached across ordinary test
   runs.
2. Starts a container from that image with `ear/target/camunda-demo.ear`
   copied into `standalone/deployments/` (not baked into the image, so
   rebuilding the EAR never invalidates the Docker layer cache), and waits
   for `/camunda-engine/engine-rest/engine` to return `200`.
3. Runs the same checks as [Verify the container integration](#verify-the-container-integration)
   above, but as real assertions: polls for the process definition to
   deploy, starts an instance, polls history until `COMPLETED`, and - the
   one that actually matters for this project's whole premise - proves
   *this specific instance's* async job ran on JBoss's managed pool, not
   just that the pool was used by something. `completed-task-count`
   increasing alone doesn't prove that (`DemoProcessDeployer`'s own REST
   call runs on the same shared pool), so the test also looks up the exact
   job id Camunda's own History REST API recorded for this process
   instance, and asserts a single log line shows *that* job id executing
   on an `EE-ManagedExecutorService-default-Thread-N` thread - tying a
   fact from an independent source (the engine's own history) to a fact
   about which thread ran it, rather than asserting the two separately and
   hoping they're related.

**This test setup found two real bugs** that had been sitting in the
"unverified assumption" notes elsewhere in this README, neither of which
showed up any other way (the project compiled and packaged fine both
times):

- `CamundaEngineBootstrap` (originally a `ServletContextListener`, now a
  CDI bean - see the Architecture bullet above) replaces a listener class
  (`org.camunda.bpm.engine.test.impl.servlet.listener.ProcessEnginesServletContextListener`)
  that turned out not to exist in `camunda-engine:7.19.0` at all - a bad
  class name from earlier, unverified web research. Deployment failed with
  `ClassNotFoundException` the first time this test actually ran the EAR
  in a container.
- `web.xml`'s `FetchAndLockContextListener` and
  `jboss-deployment-structure.xml`'s exclusion of WildFly's built-in
  `resteasy-json-binding-provider` module (see the REST API bullet under
  Architecture above) - without them, every External Task `fetchAndLock`
  either NPE'd server-side or returned dates the client couldn't parse.

Both are exactly the kind of thing that looks correct by reading the code
and the documentation, and only breaks when something real deploys and
runs it end to end.

## Why the datasource lives on the server

Datasources *can* be bundled as a deployable `*-ds.xml` inside an EAR
(along with the JDBC driver, auto-detected via its `META-INF/services/java.sql.Driver`).
This project used to do exactly that, but it depends on JBoss's
auto-generated driver-name convention for a driver jar bundled inside a
deployment, which is undocumented enough (and version-sensitive enough)
that it's a worse trade than just registering the datasource once via CLI.
Configuring it on the server also matches how you'd realistically manage a
production datasource anyway — connection pool tuning, credentials, and
swapping in a real (ideally XA) database are server/ops concerns, not
something you want tied to an application's deployment lifecycle.

## Version targets

JBoss EAP 7.4 (Jakarta EE 8, `javax.*` namespace) + Camunda 7.19–7.22. This
pairing predates Camunda's Jakarta EE 10 (`jakarta.*`) support, which
targets WildFly 27+ via separate `-jakarta` artifacts — don't mix the two.

## License

[Apache License 2.0](LICENSE).
