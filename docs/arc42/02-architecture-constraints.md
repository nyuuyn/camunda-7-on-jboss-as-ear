[← back to index](README.md)

# 2. Architecture Constraints

## 2.1 Technical Constraints

| Constraint | Reason |
|---|---|
| **JBoss EAP 7.4** as the nominal target (Jakarta EE 8, `javax.*` namespace) | Fixes the Camunda version range to 7.19-7.22 (the last line built against `javax.*`; 7.19+ also ships parallel `-jakarta` artifacts for WildFly 27+/Jakarta EE 10, which are a different, incompatible target - see [ADR: WildFly as a stand-in for EAP](09-architecture-decisions.md)). |
| **No Camunda WildFly Subsystem** | This is the project's core premise, not an incidental limitation - see [Introduction and Goals](01-introduction-and-goals.md). Ruling it out forces every piece of container integration (thread pool, transactions, datasource, REST) to be assembled by hand. |
| **Java 11** | The JDK version JBoss EAP 7.4 targets. |
| **Maven multi-module build** | Matches how a real EAR project is structured and built; also required for `maven-ear-plugin` to assemble the final artifact. |
| **Real EAP images are not freely available** | `registry.redhat.io` requires a Red Hat subscription. Automated tests and CI therefore run against WildFly 26.1.3.Final (EAP 7.4's upstream, same `javax.*` codebase generation) rather than real EAP - see [Risks and Technical Debt](11-risks-and-technical-debt.md) for what that leaves unverified. |
| **Docker required for integration tests** | Testcontainers needs a Docker daemon. Kept strictly opt-in (`mvn verify`, not `mvn package`) so the base build has no such requirement - see [Quality Requirements](10-quality-requirements.md). |

## 2.2 Organizational Constraints

| Constraint | Reason |
|---|---|
| Solo-maintained demo/reference project | No team conventions to inherit; conventions established here are the project's own (documented in [Crosscutting Concepts](08-crosscutting-concepts.md)). |
| Public repository, Apache 2.0 licensed | Anyone should be able to clone, build, and run the tests without special access - reinforces the "no proprietary EAP image" constraint above. |
| GitHub Actions CI on `ubuntu-latest` (GitHub-hosted) | Free for public repositories, has Docker preinstalled - no self-hosted infrastructure needed for the integration tests to run in CI. |

## 2.3 Conventions

| Convention | Reason |
|---|---|
| No Spring dependency (`camunda-engine-spring` excluded) | Pure JEE approach - see [ADR: no Spring dependency](09-architecture-decisions.md). |
| Process application communicates with the engine only over HTTP (REST + External Task Client), never shares a classloader | See [ADR: decouple via REST only](09-architecture-decisions.md). |
| Every non-obvious claim in this documentation is either verified against a real deployment or explicitly flagged as unverified | See [Introduction and Goals §1.2](01-introduction-and-goals.md) and [Risks and Technical Debt](11-risks-and-technical-debt.md). |
