[← back to index](README.md)

# 6. Runtime View

## 6.1 Scenario: EAR Startup

Two subdeployments initialize independently and racily - by design (see
[ADR: decouple via REST only](09-architecture-decisions.md)) - so
`DemoProcessDeployer` has to tolerate the engine not being ready yet.

```mermaid
sequenceDiagram
    participant CDI as CDI (Weld)
    participant CEB as CamundaEngineBootstrap
    participant PE as ProcessEngines / engine
    participant MJE as ManagedJobExecutor
    participant DPD as DemoProcessDeployer (EJB @Startup)
    participant REST as CamundaRestApplication (/engine-rest)

    par camunda-engine.war starts
        CDI->>CEB: @Initialized(ApplicationScoped.class)
        CEB->>PE: ProcessEngines.init()
        PE->>PE: parse camunda.cfg.xml
        PE->>MJE: init() - JNDI lookup ManagedExecutorService
        PE-->>REST: engine registered, REST resources now servable
    and process-application.jar starts
        DPD->>REST: POST /deployment/create (demo-process.bpmn)
        REST-->>DPD: HTTP 404 (war not deployed yet) or connection refused
        DPD->>DPD: retry after 2s (up to 30 attempts)
        DPD->>REST: POST /deployment/create
        REST-->>DPD: HTTP 200 - deployed
    end
```

Confirmed against a real container (see [integration tests](../../integration-test)):
a handful of `HTTP 404`/connection-refused attempts in the first couple of
seconds, then both sides settle - no manual intervention needed.

## 6.2 Scenario: Starting and Completing the Demo Process

This is the scenario that exercises *both* halves of the container
integration story - the Job Executor's thread pool, and the fully
REST-decoupled external task worker - in one flow.

```mermaid
sequenceDiagram
    participant Client
    participant REST as /engine-rest
    participant Engine as Process Engine
    participant MJE as ManagedJobExecutor
    participant MES as JBoss ManagedExecutorService
    participant ETW as DemoExternalTaskWorker

    Client->>REST: POST /process-definition/key/demoProcess/start
    REST->>Engine: start process instance
    Engine->>Engine: create async continuation Job (asyncBefore)
    Engine->>MJE: executeJobs([jobId], ...)
    MJE->>MES: executorService.execute(...)
    MES-->>MJE: runs on EE-ManagedExecutorService-default-Thread-N
    MJE->>Engine: run the real job (transition into external task)
    Engine-->>Engine: task now fetchable via External Task API

    loop long-polling
        ETW->>REST: POST /external-task/fetchAndLock (topic demo-topic)
    end
    REST-->>ETW: locked task
    ETW->>REST: POST /external-task/{id}/complete
    REST->>Engine: complete task
    Engine-->>Engine: process instance reaches end event (COMPLETED)
```

The [integration test suite](../../integration-test) asserts this scenario
end-to-end, and specifically correlates the *exact* job id (looked up from
the engine's own History REST API) with a log line showing that job
executing on an `EE-ManagedExecutorService-default-Thread-N` thread -
see [Risks and Technical Debt §11.4](11-risks-and-technical-debt.md) for
why a weaker check (thread name present *somewhere*) wasn't good enough.
