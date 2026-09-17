# Camunda 7 on JBoss EAP as a self-contained EAR

[![CI](https://github.com/nyuuyn/camunda-7-on-jboss-as-ear/actions/workflows/ci.yml/badge.svg)](https://github.com/nyuuyn/camunda-7-on-jboss-as-ear/actions/workflows/ci.yml)

**Goal:** demonstrate that Camunda 7 can run as a genuinely
container-integrated Java EE EAR on a JEE application server (JBoss EAP) -
without installing the Camunda WildFly Subsystem, and without the engine
falling back to managing its own resources. Concretely: its transactions
run under the container's JTA `TransactionManager`, its database access
goes through a container-managed datasource, and - the part that's easy to
get wrong - its **Job Executor's worker threads run on the application
server's own managed thread pool** instead of a self-managed one, which is
verified against a real, running container, not just asserted.

📖 **For the full design, rationale, and every decision behind this
(including two real bugs the integration tests caught), see the
[architecture documentation](docs/arc42/README.md).**

## Project layout

```
camunda-engine (war)         -- the process engine, bootstrapped in-EAR,
                                 no Camunda subsystem, thread pool routed
                                 through JBoss's ManagedExecutorService
process-application (ejb)    -- a demo BPMN process, deployed and driven
                                 entirely over REST - no compile-time
                                 dependency on camunda-engine
camunda-web-ui (war)         -- Cockpit/Tasklist/Admin, overlaying
                                 Camunda's prebuilt webapp WAR; a separate
                                 subdeployment that shares the engine via
                                 ear/lib/, not REST
ear                          -- assembles all three into one deployable EAR
integration-test             -- Testcontainers suite proving the above
docs/arc42/                  -- full architecture documentation
```

## Build

```sh
mvn clean package
```

Produces `ear/target/camunda-demo.ear`. No Docker or application server
needed for this step.

## Deploy

No server-side setup at all - just deploy the EAR:

```sh
cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
```

The `ProcessEngine` datasource is declared inside the EAR via
`@DataSourceDefinition` (`camunda-engine`'s `CamundaEngineBootstrap`), and
its H2 driver is bundled straight into `camunda-engine.war`'s own
`WEB-INF/lib` (`camunda-engine/pom.xml`, `runtime`-scoped) - nothing to
install on the server beforehand.

## Run with Docker

No local JDK/Maven/JBoss install needed - this builds the EAR and bakes it
into a runnable WildFly image in one go:

```sh
docker build -t camunda-demo .
docker run -p 8080:8080 camunda-demo
```

Once it's up, `http://localhost:8080/camunda-web-ui/` (Cockpit/Tasklist/
Admin) and `http://localhost:8080/camunda-engine/engine-rest/` (the REST
API) are reachable the same way they would be on a real deployment - the
image only differs from a bare JBoss EAP host in how WildFly itself gets
there (see [Deployment View §7.4](docs/arc42/07-deployment-view.md#74-standalone-docker-image)).

If you have access to a real EAP image, point `BASE_IMAGE` at it to skip
building the WildFly-on-JDK-17 stand-in stage entirely:

```sh
docker build --build-arg BASE_IMAGE=your-eap-image:tag -t camunda-demo .
```

This top-level `Dockerfile` is separate from
`integration-test/src/test/resources/docker/Dockerfile`, which
Testcontainers owns for `mvn verify` and copies the EAR in per test run
instead of baking it in.

## Verify

```sh
mvn verify
```

(Docker daemon required; this is opt-in and separate from `mvn clean
package`.) Builds a WildFly container - a freely-pullable stand-in for
JBoss EAP 7.4, since real EAP images need a Red Hat subscription - deploys
the EAR onto it, and asserts the demo process runs end-to-end *and* that
its job execution actually lands on JBoss's managed thread pool, not just
a plausible-looking thread name. See
[Quality Requirements](docs/arc42/10-quality-requirements.md) for exactly
what's checked and how.

To check the same things by hand against your own deployment, see
[Runtime View](docs/arc42/06-runtime-view.md).

## License

[Apache License 2.0](LICENSE).
