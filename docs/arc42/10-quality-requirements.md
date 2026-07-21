[← back to index](README.md)

# 10. Quality Requirements

## 10.1 Quality Tree

```
Quality
├── Verifiable container integration
│   ├── Job execution runs on JBoss's managed thread pool (not self-managed)
│   └── Claims are checked against a real deployment, not just documented
├── Decoupling
│   ├── process-application has no compile-time dependency on camunda-engine
│   └── process-application cannot see camunda-engine's classes at runtime
├── Buildability / accessibility
│   ├── mvn clean package succeeds with no Docker, no special access
│   └── mvn verify succeeds with only a local Docker daemon (no EAP subscription)
└── Documentation honesty
    └── Unverified assumptions are explicitly flagged as such
```

## 10.2 Quality Scenarios

| # | Scenario | How it's checked |
|---|---|---|
| 1 | A job's execution is submitted to the Job Executor. It must run on a thread belonging to JBoss's `ManagedExecutorService`, correlated to that *specific* job (not just "some thread with that name pattern exists somewhere"). | `CamundaEarIT.jobExecutorRunsOnJBossManagedThreadPool` - looks up the job id for a specific process instance via the History REST API, then asserts a log line shows *that* job id executing on an `EE-ManagedExecutorService-default-Thread-N` thread, plus `completed-task-count` on the managed-executor-service resource increasing. |
| 2 | The demo process, deployed fresh to a real container with no manual setup beyond the datasource, must complete end-to-end (async job → external task → completion). | `CamundaEarIT.demoProcessCompletesEndToEnd` - polls the History REST API until `state=COMPLETED`. |
| 3 | `process-application` must not be able to see `camunda-engine`'s classes, even though they ship in the same EAR. | Enforced structurally (`ear-subdeployments-isolated=true`), not just tested - a compile-time dependency on `camunda-engine` in `process-application`'s `pom.xml` would be the failure mode, and there isn't one. |
| 4 | Building the project must not require Docker or any JBoss/EAP access. | `mvn clean package` from the repository root; verified as part of every change to the `integration-test` module specifically to make sure Failsafe's binding didn't leak into the default build lifecycle. |
| 5 | Running the full test suite must not require a paid Red Hat subscription. | `mvn verify` targets WildFly (see [ADR-7](09-architecture-decisions.md)), pulled from the public `quay.io/wildfly/wildfly` registry. |
| 6 | Anyone reading the documentation should be able to tell which claims were checked against a real deployment and which are inference/assumption. | Every "unverified" note in this documentation and the top-level README is a deliberate, explicit flag, not an omission - several turned out to be wrong (see [Risks and Technical Debt](11-risks-and-technical-debt.md)), which is exactly why this distinction matters in practice, not just in principle. |
