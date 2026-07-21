package com.example.camunda;

import org.camunda.bpm.client.ExternalTaskClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import java.util.logging.Logger;

/**
 * Replaces the old in-process JavaDelegate: this is the external task
 * handler for topic "demo-topic" (see process-application's
 * demo-process.bpmn). It only ever talks to the engine via the External
 * Task REST API (long-polling fetch-and-lock, then complete/fail) - no
 * compile-time dependency on camunda-engine, same as process-application.
 *
 * The client's fetch-and-lock loop is, by design, a single lightweight
 * polling thread (the client is meant to be run standalone, even in a
 * separate process from the engine) - not something worth forcing onto
 * JBoss's ManagedExecutorService the way ManagedJobExecutor's job
 * *execution* pool was. That was a real thread pool doing potentially
 * many concurrent invocations; this is one polling loop, same class of
 * thing as the engine's own job-acquisition thread.
 */
@Singleton
@Startup
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class DemoExternalTaskWorker {

    private static final Logger LOG = Logger.getLogger(DemoExternalTaskWorker.class.getName());
    private static final String TOPIC = "demo-topic";

    private ExternalTaskClient client;

    @PostConstruct
    public void start() {
        String baseUrl = System.getProperty("camunda.engine.rest.baseUrl", "http://localhost:8080/camunda-engine/engine-rest");

        client = ExternalTaskClient.create()
                .baseUrl(baseUrl)
                .build();

        client.subscribe(TOPIC)
                .handler((externalTask, externalTaskService) -> {
                    LOG.info("Handling external task " + externalTask.getId() + " (topic " + TOPIC
                            + ") on thread [" + Thread.currentThread().getName() + "]");
                    externalTaskService.complete(externalTask);
                })
                .open();
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            client.stop();
        }
    }
}
