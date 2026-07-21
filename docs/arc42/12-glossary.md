[← back to index](README.md)

# 12. Glossary

| Term | Meaning |
|---|---|
| **EAR** | Enterprise Archive - the top-level Java EE deployment unit that bundles one or more WARs/EJB-JARs plus shared libraries. |
| **WAR** | Web Archive - a deployable web application module (servlets, JAX-RS, etc.). `camunda-engine` is one. |
| **EJB** | Enterprise JavaBean - a managed component with container-provided lifecycle, concurrency, and transaction semantics. `process-application`'s deployer and worker are `@Singleton @Startup` EJBs. |
| **CDI** | Contexts and Dependency Injection - Java EE's dependency injection and lifecycle-event framework (Weld is JBoss/WildFly's implementation). Used by `CamundaEngineBootstrap`. |
| **JTA** | Java Transaction API - the container's transaction manager interface. The engine's transactions run under the container's `TransactionManager`, looked up via JNDI. |
| **JNDI** | Java Naming and Directory Interface - the lookup mechanism (`java:...` names) used throughout this project instead of passing Java object references directly (datasource, transaction manager, managed executor service are all resolved this way). |
| **ManagedExecutorService** | The JSR-236/Jakarta Concurrency API type for a container-managed thread pool. `java:jboss/ee/concurrency/executor/default` is the one this project routes job execution through. |
| **Job Executor** | Camunda's internal component responsible for acquiring due asynchronous jobs from the database and executing them. `ManagedJobExecutor` is this project's custom implementation. |
| **Async continuation** (`camunda:asyncBefore`) | A BPMN modeling flag that makes the engine create a Job (executed by the Job Executor) to transition into an activity, instead of doing it inline on the calling thread. Used on the demo process's service task specifically so the Job Executor has something to do. |
| **External Task** | A Camunda task-execution pattern where a worker fetches, locks, and completes work over REST (long-polling), rather than the engine invoking in-process delegate code. `DemoExternalTaskWorker` is such a worker. |
| **BPMN** | Business Process Model and Notation - the XML format (`demo-process.bpmn`) describing the process. |
| **Process Engine** | Camunda's core runtime component that executes BPMN process instances. |
| **Camunda WildFly Subsystem** | Camunda's officially supported JBoss/WildFly integration - a server-side module this project deliberately does not use (see [ADR-1](09-architecture-decisions.md)). |
| **`javax.*` vs `jakarta.*`** | The two Java EE / Jakarta EE package namespaces. JBoss EAP 7.4 (this project's target) uses `javax.*` (Jakarta EE 8); WildFly 27+ switched to `jakarta.*` (Jakarta EE 10) - a different, incompatible target this project does not support. |
| **Testcontainers** | The Java library used by `integration-test` to programmatically build and run Docker containers as part of the test suite. |
| **`jcmd`** | A JDK diagnostic tool (`Thread.print`, etc.) - tried and abandoned as a verification mechanism; see [Risks and Technical Debt §11.4](11-risks-and-technical-debt.md). |
