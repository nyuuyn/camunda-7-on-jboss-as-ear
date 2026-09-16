---
name: jboss-eap
description: |
  Use this skill for JBoss EAP/WildFly application-server concerns in this project — EAR assembly and jboss-deployment-structure.xml, the datasources subsystem, JNDI names, the built-in ManagedExecutorService, jboss-cli.sh scripting, and running/testing against WildFly as a stand-in for JBoss EAP 7.4.

  Use for: adding or editing the @DataSourceDefinition-based datasource (camunda-engine's CamundaEngineBootstrap); changing subdeployment isolation or module exclusions in jboss-deployment-structure.xml; JNDI lookups for JTA/datasources/managed executors; debugging deployment failures on JBoss/WildFly; adjusting the Testcontainers/Docker WildFly test image; swapping WildFly for a real EAP image.

  Do not use for: Camunda engine configuration itself (camunda.cfg.xml, ManagedJobExecutor, REST embedding — see the camunda7 skill), or Camunda 8 tooling (use camunda-skills:*).
---

# JBoss EAP / WildFly deployment (this project)

This project's rule of thumb: **everything goes inside the EAR — including
the datasource declaration AND its JDBC driver.** There is no required
server-side setup at all (ADR-4 in
[`docs/arc42/09-architecture-decisions.md`](../../../docs/arc42/09-architecture-decisions.md)).
No Camunda WildFly Subsystem module is installed — see that same file's
ADR-1 before "fixing" anything by reaching for the subsystem.

## JNDI names this project depends on

| JNDI name | What it is | Where it's configured |
|---|---|---|
| `java:app/datasources/ProcessEngine` | The engine's datasource | Declared via `@DataSourceDefinition` on `CamundaEngineBootstrap` (camunda-engine); referenced from `camunda.cfg.xml`'s `dataSourceJndiName`. Its H2 driver is bundled into `camunda-engine.war`'s own `WEB-INF/lib` (`camunda-engine/pom.xml`, `runtime` scope) — no server-side install needed |
| `java:/TransactionManager` | JTA transaction manager | Present out of the box; referenced from `camunda.cfg.xml`'s `transactionManagerJndiName` |
| `java:jboss/ee/concurrency/executor/default` | The `ee` subsystem's default `ManagedExecutorService` | Present out of the box on JBoss EAP/WildFly; looked up by `ManagedJobExecutor.init()` and also injected via `@Resource ManagedExecutorService` in `DemoProcessDeployer` |

If a deployment fails with a `NameNotFoundException` on
`java:app/datasources/ProcessEngine`, check the EAR actually deployed
cleanly (see below) — this datasource only exists once the EAR itself is
up; there's no separate server-side resource to have forgotten to create.

## Datasource setup

The `ProcessEngine` datasource is declared **inside the EAR**, via
`@DataSourceDefinition` on `com.example.camunda.CamundaEngineBootstrap`
(camunda-engine) — not a server-side `data-source add`. Its H2 driver is
bundled straight into `camunda-engine.war`'s own `WEB-INF/lib`
(`camunda-engine/pom.xml` ships `com.h2database:h2` at `runtime` scope,
not `test`). **There is no server-side setup required at all** — confirmed
by deploying the EAR to a completely stock WildFly with nothing
pre-installed and getting a clean boot.

**Two non-obvious things about `@DataSourceDefinition` on WildFly/EAP**,
both found by deploying and reading the resulting errors, not
documentation:

1. **`className` resolution is not the same as a CLI datasource's
   `driver-class-name`.** A CLI `data-source add` matches its driver by
   name against the datasources subsystem's `jdbc-driver` registry.
   `@DataSourceDefinition` instead loads `className` directly through the
   *deployment's own* module classloader — which is exactly why bundling
   the driver jar in the WAR (rather than installing it as a server
   module) is enough on its own; no `jboss-deployment-structure.xml`
   dependency entry needed. (Deploying with the class visible nowhere
   throws `ClassNotFoundException: org.h2.Driver`.)
2. **`className` must be a `javax.sql.DataSource`/`XADataSource`/
   `ConnectionPoolDataSource` implementation, not a `java.sql.Driver`.**
   `org.h2.Driver` (what the CLI/`*-ds.xml` world wants) fails deployment
   with `WFLYJCA0117: ... is not a valid javax.sql.DataSource
   implementation`. H2's actual `DataSource` impl is
   `org.h2.jdbcx.JdbcDataSource` — that's what `CamundaEngineBootstrap`
   uses.

**H2 is demo-only** (single connection pool, no HA). For anything beyond a
local demo, swap in a real — ideally XA — database's driver dependency
(Oracle/Postgres/etc.) and point `@DataSourceDefinition` at a matching
`DataSource` implementation class so the engine's DB work fully
participates in JTA two-phase commit.

This is different from bundling a driver *jar* as an auto-detected
`*-ds.xml` driver, which was tried and reverted before (ADR-4's original
decision, and still a bad idea): that depends on JBoss's undocumented,
version-sensitive auto-generated driver-name convention. A plain Maven
runtime dependency loaded via `@DataSourceDefinition`'s normal
classloading doesn't have that problem.

## EAR structure and `jboss-deployment-structure.xml`

`ear/src/main/application/META-INF/jboss-deployment-structure.xml`
controls classloading across the EAR's subdeployments:

```xml
<jboss-deployment-structure xmlns="urn:jboss:deployment-structure:1.2">
  <ear-subdeployments-isolated>true</ear-subdeployments-isolated>
  <sub-deployment name="camunda-engine.war">
    <exclusions>
      <module name="org.jboss.resteasy.resteasy-json-binding-provider"/>
    </exclusions>
  </sub-deployment>
</jboss-deployment-structure>
```

- **`ear-subdeployments-isolated=true`**: `camunda-engine.war` and
  `process-application.ejb` cannot see each other's classes. This is
  deliberate — see the camunda7 skill's "decoupling via REST only"
  section. If you add a new subdeployment that legitimately needs to share
  classes with another (not the engine), you'll need an explicit
  `<module>`/dependency entry here; don't just disable isolation project-wide.
- **`ear/lib/`** (shared engine/REST/client jars) stays visible to *all*
  subdeployments regardless of this setting — isolation only affects
  cross-subdeployment visibility, not the shared EAR library directory.
- **The `resteasy-json-binding-provider` exclusion is load-bearing.**
  Without it, WildFly/EAP's built-in JSON-B (Yasson) provider wins over
  the bundled Jackson provider for `application/json`, and dates get
  serialized as `...Z[UTC]` (JSON-B's `ISO_ZONED_DATE_TIME` default) —
  which breaks the External Task Client's date parsing silently. If you
  add another JAX-RS-consuming subdeployment and see the same silent
  deserialization failure, check whether it needs the same exclusion.
- **No `<module>` dependency for the H2 driver** — `camunda-engine.war`
  already ships `org.h2.jdbcx.JdbcDataSource` in its own `WEB-INF/lib`
  (see "Datasource setup" above), so there's nothing for
  `jboss-deployment-structure.xml` to wire up. If a real deployment ever
  needs a driver installed as a server module instead of bundled, this is
  where you'd add a `<dependencies><module name="..."/></dependencies>`
  entry for `camunda-engine.war`.

## Deploying

```sh
mvn clean package                                        # produces ear/target/camunda-demo.ear
cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
```

Nothing is installed on the server beforehand — no subsystem module, no
driver module, no other config, and no `data-source` resource to check
(the datasource itself only exists once the EAR is deployed). If
deployment fails, check `$JBOSS_HOME/standalone/log/server.log` for the
actual exception; this project's history includes bugs (a nonexistent
listener class, the JSON-B conflict above, the `@DataSourceDefinition`
classloading/DataSource-vs-Driver issues above) that only surfaced this
way, never at build/package time.

## WildFly as an EAP 7.4 stand-in (tests, CI)

`integration-test/` and CI run against WildFly 26.1.3.Final — EAP 7.4's
freely-available upstream, same `javax.*`-generation codebase — because
real EAP images need a `registry.redhat.io` subscription. quay.io never
published a `26.1.3.Final-jdk17` tag (its jdk17 tags only start at
WildFly 28/Jakarta EE 10, outside this project's `javax.*` target — see
`docs/arc42/02-architecture-constraints.md`), so the Dockerfile's
`wildfly-jdk17` stage copies `$JBOSS_HOME` out of quay.io's jdk11-tagged
image (a normal, fast registry pull) onto a JDK 17 base, instead of
downloading the WildFly release tarball from GitHub Releases directly —
that CDN throttled to ~20 KB/s in testing, turning a ~200MB download into
hours. The base image is a Dockerfile `ARG` (`BASE_IMAGE`), swappable for
a real EAP image by anyone with registry access — doing so skips the
`wildfly-jdk17` build stage entirely.

**Known, accepted risk**: WildFly and EAP are close but not identical
(Red Hat patches, different defaults, different release cadence). In
particular, `java:jboss/ee/concurrency/executor/default`'s exact JNDI
name/behavior is assumed — not independently confirmed — identical between
WildFly 26 and EAP 7.4. Don't present integration-test results as proof
this works on real EAP; say explicitly that it's only verified against
WildFly.

The test image bakes in **nothing datasource-related at all** — no
`module add`, no `jdbc-driver=h2:add`, no `data-source add`. The
`ProcessEngine` datasource and its H2 driver are both bundled in the EAR
via `@DataSourceDefinition`/`camunda-engine.war`'s own `WEB-INF/lib`, so
this shape deploys exactly the same way production does (§7.1 of the
deployment view). The EAR itself is still copied into the container **at
test-run start**, not baked into the image, to keep the image layer cache
valid across ordinary EAR rebuilds.

**`WORKDIR` in the Dockerfile is `$JBOSS_HOME`, not the image's default.**
The `@DataSourceDefinition`'s H2 URL is a relative file path
(`./camunda-h2-database/...`), which resolves against the JVM's
`user.dir` — i.e., wherever `standalone.sh` was launched from. `$JBOSS_HOME`
is `chmod -R g+rw`'d for the `jboss` user during the image build; its
parent (`/opt/jboss`) is not. Deploying with a `WORKDIR` outside
`$JBOSS_HOME` throws `Error while creating file
/opt/jboss/camunda-h2-database` at boot. If you change the Dockerfile's
base stage, keep `WORKDIR` somewhere the `jboss` user can actually write
to.

See `docs/arc42/07-deployment-view.md §7.2` before changing the
Dockerfile/test harness — the caching structure is intentional.

## CI

`.github/workflows/ci.yml`, `ubuntu-latest`: checkout → JDK 17 setup →
`mvn -B verify`. GitHub-hosted runners have Docker preinstalled, so the
Testcontainers-based integration tests need no extra CI setup.

## Quick command reference

```sh
# Build and deploy - no server-side setup needed first
mvn clean package
cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/

# Run the Testcontainers/WildFly integration suite (needs Docker)
mvn verify
```
