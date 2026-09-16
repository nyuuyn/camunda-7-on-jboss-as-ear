---
name: camunda7
description: |
  Use this skill when working on the Camunda 7 process engine in this project — the camunda-engine WAR's plain-JEE bootstrap (no WildFly Subsystem, no Spring), the container-managed Job Executor, in-EAR REST API embedding, and the REST-only process-application pattern.

  Use for: editing camunda.cfg.xml or ManagedJobExecutor; adding or changing BPMN processes deployed the DemoProcessDeployer way (REST, with retries); adding External Task workers; debugging engine bootstrap, the embedded /engine-rest API, or job-executor behavior; deciding whether new code belongs in camunda-engine or process-application.

  Do not use for: Camunda 8/Zeebe work (this project is Camunda 7 — a different product; use the camunda-skills:* plugin skills for C8 instead), or JBoss/WildFly server- and EAR-level mechanics (datasource setup, jboss-deployment-structure.xml, JNDI plumbing — see the jboss-eap skill).
---

# Camunda 7 on a plain JEE container (this project)

This project deliberately does **not** use the Camunda WildFly Subsystem or
`camunda-engine-spring`. Every piece of container integration is assembled
by hand and verified against a real running container
(`integration-test/`, Testcontainers). Before changing anything here, read
[`docs/arc42/04-solution-strategy.md`](../../../docs/arc42/04-solution-strategy.md)
and [`08-crosscutting-concepts.md`](../../../docs/arc42/08-crosscutting-concepts.md)
— they carry the reasoning this file assumes.

## Key files

| File | Role |
|---|---|
| `camunda-engine/src/main/resources/camunda.cfg.xml` | Declares `processEngineConfiguration` (`JtaProcessEngineConfiguration`) and `jobExecutor` beans. **Plain `spring-beans.xsd` XML** — this is the format `camunda-engine`'s built-in classpath-scan bootstrap parses internally, regardless of whether `camunda-engine-spring` is present. It is not "using Spring." |
| `com.example.camunda.CamundaEngineBootstrap` | `@ApplicationScoped` CDI bean; `@Observes @Initialized(ApplicationScoped.class)`/`@Destroyed` force eager `ProcessEngines.init()`/`.destroy()` at webapp start/stop (CDI beans are lazy by default — a plain `@ApplicationScoped` bean without this would never fire). Also `@Produces ProcessEngine` for `@Inject`. Requires `WEB-INF/beans.xml` to exist or the bean is never discovered. |
| `com.example.camunda.ManagedJobExecutor` | Custom `JobExecutor` (extends the engine's abstract `JobExecutor`, not `ThreadPoolJobExecutor`). Looks up an `ExecutorService` via JNDI in `init()` and submits job-execution `Runnable`s to it. Pure `javax.naming` + `java.util.concurrent` — no Spring types, works standalone too. |
| `com.example.camunda.CamundaRestApplication` | Plain `javax.ws.rs.core.Application` embedding `camunda-engine-rest-core`'s resources under `/engine-rest`, instead of deploying Camunda's separate `engine-rest.war`. |
| `META-INF/services/org.camunda.bpm.engine.rest.spi.ProcessEngineProvider` | Wires `ContainerManagedProcessEngineProvider` so REST requests resolve against the plain `ProcessEngines` registry (no subsystem/`BpmPlatform`). |
| `process-application/.../DemoProcessDeployer` | `@Singleton @Startup` EJB. `POST`s BPMN to `/engine-rest/deployment/create` at startup, retried via `@Resource ManagedExecutorService` (not a raw `Thread`) since there's no ordering guarantee between EAR subdeployments. |
| `process-application/.../DemoExternalTaskWorker` | `@Singleton @Startup` EJB using `camunda-external-task-client` to long-poll/fetch/complete over REST. |

## Core pattern: decoupling via REST only

`process-application` has **zero compile-time dependency** on
`camunda-engine` and cannot see its classes at runtime
(`ear-subdeployments-isolated=true` — see the jboss-eap skill). It talks to
the engine only through:

- The **deployment REST API** (`POST /engine-rest/deployment/create`) to deploy BPMN.
- The **External Task REST API** (`fetchAndLock`/`complete`) via `camunda-external-task-client`.

**If you're adding a new process or worker**, follow this same shape:
new BPMN + its EJB deployer/worker go in `process-application` (or a new
sibling module), never a compile-time dependency on `camunda-engine`. Don't
reach for `EjbProcessApplication`/`processes.xml` (the conventional Camunda
pattern) — that was tried and deliberately abandoned (see ADR-3 in
`09-architecture-decisions.md`) because it couples the process application
to the engine's classloader.

**`camunda-web-ui` (Cockpit/Tasklist/Admin) is the one deliberate
exception** — it needs direct Java-API access to the running
`ProcessEngine` (`ProcessEngines.getProcessEngines()`), so REST-only
doesn't apply to it. It's still its own EAR subdeployment, but it resolves
the shared engine through `ear/lib/camunda-engine.jar` (visible to every
subdeployment regardless of `ear-subdeployments-isolated`) instead of
REST — see ADR-9 and
[Building Block View §5.4](../../../docs/arc42/05-building-block-view.md).
Don't use this module as a precedent for a new process/worker module;
it's justified specifically because Cockpit/Tasklist/Admin have no
REST-only integration mode.

## Adding/changing engine configuration

- Edit `camunda.cfg.xml` as plain `<bean>`/`<property>` XML. Both
  `dataSourceJndiName` and `transactionManagerJndiName` are **JNDI name
  strings**, not object references — the engine does its own
  `InitialContext` lookup internally. Don't add `jee:jndi-lookup` or any
  Spring namespace; don't add `camunda-engine-spring` as a dependency.
- The file **must** be named exactly `camunda.cfg.xml` and sit at the
  classpath root — that exact name is what the engine's classpath scan
  looks for, one `ProcessEngine` per file found.
- `jobExecutorDeploymentAware=true` + `jobExecutorActivate=true` are both
  set; leave them unless you have a specific reason to change acquisition
  behavior.

## Job Executor: what's container-managed and what isn't

Only the **execution** thread pool is container-managed
(`ManagedJobExecutor` → JNDI-looked-up `ExecutorService`). Two things
deliberately stay self-managed — don't try to route these through a
managed executor, and don't treat their self-managed-ness as a bug:

- The Job Executor's **acquisition** thread (a single lightweight polling
  loop — true even under Camunda's *official* WildFly subsystem).
- `ExternalTaskClient`'s long-polling thread (`TopicSubscriptionManager`) —
  designed to run standalone, possibly in a separate process from the
  engine entirely.

The dividing line for "should this be container-managed": does it do
potentially-concurrent, potentially-slow *work* (→ yes, route it), or is it
a single lightweight loop waiting on something (→ no, leave it). See
`08-crosscutting-concepts.md §8.1` for the full table, including why
`DemoProcessDeployer`'s retry loop *is* routed through
`ManagedExecutorService` despite being "just a startup retry."

## REST API embedding gotchas

Two non-obvious things are required for the embedded `/engine-rest` API to
actually work — both were found by deploying and reading the resulting
errors, not documentation, so don't assume a fresh REST resource "just
works":

1. **`FetchAndLockContextListener`** must be declared in `web.xml`.
   Without it, `fetchAndLock` throws a server-side NPE
   (`getFetchAndLockHandler()` returns null).
2. **WildFly/JBoss's built-in JSON-B provider must be excluded** for the
   engine WAR (`org.jboss.resteasy.resteasy-json-binding-provider`, in
   `jboss-deployment-structure.xml` — see the jboss-eap skill), or it wins
   over the bundled Jackson provider for `application/json` and serializes
   dates in a format (`...Z[UTC]`) the External Task Client's own bundled
   Jackson can't parse. Symptom: every `fetchAndLock` response fails to
   deserialize client-side, with no server-side error at all.

If you add new REST-consuming code (another external task client, another
REST call site) and see silent deserialization failures or 500s from
`/engine-rest`, check these two first.

## Testing philosophy: verify, don't assume

Both real bugs found in this project's history (a nonexistent bootstrap
class from unverified research; the JSON-B/Jackson conflict above) built
and packaged cleanly and only failed once actually deployed. When changing
engine bootstrap, REST wiring, or job-executor behavior:

- Don't trust that a class/API exists because research or a blog post says
  so — check it against the actual `camunda-engine` jar for the version in
  `pom.xml`.
- Prefer adding/extending an assertion in `integration-test/` (Testcontainers
  against a real WildFly container) over trusting that code "looks correct."
  See `10-quality-requirements.md` for how the existing thread-pool
  assertion is structured (correlate a specific job id from the History
  REST API with a specific log line — not two facts checked in isolation
  and assumed related).

## Camunda 7 maintenance status

Camunda 7 Community Edition is in maintenance mode (7.24 is the final
planned minor release). `camunda-engine`, `camunda-engine-rest-core`, and
`camunda-external-task-client` will see decreasing upstream activity —
factor this in for any dependency-version work.
