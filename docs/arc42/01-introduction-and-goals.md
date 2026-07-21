[← back to index](README.md)

# 1. Introduction and Goals

## 1.1 Requirements Overview

Camunda 7's officially supported way to integrate with JBoss/WildFly is the
**Camunda WildFly Subsystem**: a server-side module you install once, which
then owns the process engine, its Job Executor thread pool, and its
datasource as native JBoss services. That's a legitimate approach, but it
requires modifying the application server itself before anything can be
deployed to it.

This project asks a narrower, more demanding question: **can Camunda 7 run
as a Java EE EAR on a JEE application server (JBoss EAP) while still being
*genuinely* container-integrated** - its transactions on the container's
JTA `TransactionManager`, its database access through a container-managed
datasource, and critically, **its Job Executor's worker threads on the
application server's own managed thread pool** - without installing the
subsystem, and without the engine falling back to managing its own
resources (in particular, its own `java.util.concurrent.ThreadPoolExecutor`,
which is exactly the kind of self-managed thread pool the Java EE spec asks
applications not to create)?

A secondary requirement, added once the first was working: the process
application (the BPMN process and its task-handling code) should be
**decoupled from the engine** - deployable, testable, and replaceable
independently of it, communicating only over its public REST API rather
than sharing a classloader or JVM-internal API surface with it.

## 1.2 Quality Goals

Ranked by priority, since some of these trade off against each other:

| # | Quality Goal | Motivation |
|---|---|---|
| 1 | **Verifiable container integration** | The whole point of the project is a specific, checkable claim ("job execution threads are JBoss-managed"). If that claim can't be verified against a real, running deployment, the project has failed at its own goal. |
| 2 | **Decoupling** | The process application must not require the engine's classes, only its REST API - proven by `ear-subdeployments-isolated=true`, not just asserted in documentation. |
| 3 | **Honesty about what's unverified vs. verified** | Several early "this should work" assumptions turned out to be wrong once actually deployed (see [Risks and Technical Debt](11-risks-and-technical-debt.md)). Documentation that doesn't distinguish "I checked this" from "I assumed this" is actively misleading. |
| 4 | **Portability of the target stack** | JBoss EAP images require a paid Red Hat subscription; the project needed to remain buildable and testable by anyone, hence testing against WildFly (EAP's freely-available upstream) rather than requiring EAP access. |
| 5 | **Minimal footprint** | No dependency (Spring, the Camunda subsystem, extra libraries) is added unless it's load-bearing for a stated goal above. |

## 1.3 Stakeholders

| Role | Concern |
|---|---|
| Architect/engineer evaluating Camunda 7 on JEE app servers | Whether "properly integrated" is actually achievable without the official subsystem, and what it costs to get there. |
| Future maintainer of this repository | Understanding *why* the code looks the way it does - several design decisions (e.g. no Spring, REST-only decoupling, server-side datasource) are not the "obvious" choice and need their rationale on record. |
| Anyone deploying this to a real JBoss EAP instance | Needs the server-side setup steps and the difference between this project's WildFly-based test target and a real EAP deployment. |
