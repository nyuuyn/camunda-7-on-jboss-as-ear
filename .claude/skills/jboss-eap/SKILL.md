---
name: jboss-eap
description: |
  Use this skill for JBoss EAP/WildFly application-server concerns in this project — EAR assembly and jboss-deployment-structure.xml, the datasources subsystem, JNDI names, the built-in ManagedExecutorService, jboss-cli.sh scripting, and running/testing against WildFly as a stand-in for JBoss EAP 7.4.

  Use for: adding or editing datasources (server-config/add-datasource.cli); changing subdeployment isolation or module exclusions in jboss-deployment-structure.xml; JNDI lookups for JTA/datasources/managed executors; debugging deployment failures on JBoss/WildFly; adjusting the Testcontainers/Docker WildFly test image; swapping WildFly for a real EAP image.

  Do not use for: Camunda engine configuration itself (camunda.cfg.xml, ManagedJobExecutor, REST embedding — see the camunda7 skill), or Camunda 8 tooling (use camunda-skills:*).
---

# JBoss EAP / WildFly deployment (this project)

This project's rule of thumb: **everything goes inside the EAR except the
datasource**, which lives on the server (ADR-4 in
[`docs/arc42/09-architecture-decisions.md`](../../../docs/arc42/09-architecture-decisions.md)).
No Camunda WildFly Subsystem module is installed — see that same file's
ADR-1 before "fixing" anything by reaching for the subsystem.

## JNDI names this project depends on

| JNDI name | What it is | Where it's configured |
|---|---|---|
| `java:jboss/datasources/ProcessEngine` | The engine's datasource | Created once via `server-config/add-datasource.cli`; referenced from `camunda.cfg.xml`'s `dataSourceJndiName` |
| `java:/TransactionManager` | JTA transaction manager | Present out of the box; referenced from `camunda.cfg.xml`'s `transactionManagerJndiName` |
| `java:jboss/ee/concurrency/executor/default` | The `ee` subsystem's default `ManagedExecutorService` | Present out of the box on JBoss EAP/WildFly; looked up by `ManagedJobExecutor.init()` and also injected via `@Resource ManagedExecutorService` in `DemoProcessDeployer` |

If a deployment fails with a `NameNotFoundException` on any of these,
check the datasource actually got added (see below) before assuming code
is wrong — the first two only exist after the CLI script runs.

## Datasource setup (`server-config/add-datasource.cli`)

This is the **one** piece of server-side setup this project requires.
Run once per target server:

```sh
$JBOSS_HOME/bin/jboss-cli.sh --connect --file=server-config/add-datasource.cli
```

What it does, in order: `module add` (installs the H2 driver jar as a
JBoss module with `javax.api,javax.transaction.api` dependencies),
`jdbc-driver=h2:add`, `data-source add --name=ProcessEngine
--jndi-name=java:jboss/datasources/ProcessEngine ...`, then `:reload`.

Verify it's bound:

```sh
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  --command="/subsystem=datasources/data-source=ProcessEngine:read-resource"
```

**H2 is demo-only** (in-memory/file-based, single connection pool, no HA).
For anything beyond a local demo, replace it with a real — ideally XA —
datasource (Oracle/Postgres/etc.) so the engine's DB work fully
participates in JTA two-phase commit. Don't bundle a datasource as a
deployable `*-ds.xml` inside the EAR instead — that was tried and reverted
(ADR-4): it depends on JBoss's undocumented, version-sensitive
auto-generated driver-name convention for a driver jar embedded in a
deployment, which is a worse trade than the one-time CLI script.

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

## Deploying

```sh
mvn clean package                                        # produces ear/target/camunda-demo.ear
cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
```

Nothing else is installed on the server beyond the one datasource — no
subsystem module, no other config. If deployment fails, check
`$JBOSS_HOME/standalone/log/server.log` for the actual exception; this
project's history includes bugs (a nonexistent listener class, the JSON-B
conflict above) that only surfaced this way, never at build/package time.

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

In the test image, the datasource is baked in at **build time** via
`embed-server`/`stop-embedded-server` (edits `standalone.xml` without
booting the server, so this Docker layer stays cached across ordinary
runs) rather than the running-server CLI call used in production. The EAR
itself is copied into the container **at test-run start**, not baked into
the image, to keep that layer cache valid across ordinary EAR rebuilds.
See `docs/arc42/07-deployment-view.md §7.2` before changing the
Dockerfile/test harness — the caching structure is intentional.

## CI

`.github/workflows/ci.yml`, `ubuntu-latest`: checkout → JDK 17 setup →
`mvn -B verify`. GitHub-hosted runners have Docker preinstalled, so the
Testcontainers-based integration tests need no extra CI setup.

## Quick command reference

```sh
# Add the datasource (once per server)
$JBOSS_HOME/bin/jboss-cli.sh --connect --file=server-config/add-datasource.cli

# Inspect a resource
$JBOSS_HOME/bin/jboss-cli.sh --connect --command="/subsystem=datasources/data-source=ProcessEngine:read-resource"

# Build and deploy
mvn clean package
cp ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/

# Run the Testcontainers/WildFly integration suite (needs Docker)
mvn verify
```
