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
 * External task handler for topic "demo-topic" (see demo-process.bpmn) -
 * talks to the engine only via the External Task REST API. See
 * docs/arc42/08-crosscutting-concepts.md §8.1 for why its polling thread
 * stays self-managed, unlike ManagedJobExecutor's execution pool.
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
