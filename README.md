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
ear                          -- assembles both into one deployable EAR
integration-test             -- Testcontainers suite proving the above
server-config/                -- one-time JBoss datasource setup
docs/arc42/                  -- full architecture documentation
```

## Build

```sh
mvn clean package
```

Produces `ear/target/camunda-demo.ear`. No Docker or application server
needed for this step.

## Deploy

1. Run `server-config/add-datasource.cli` once against your JBoss EAP
   instance (see `server-config/README.md`) - the only server-side setup
   this project requires.
2. ```sh
   cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
   ```

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
