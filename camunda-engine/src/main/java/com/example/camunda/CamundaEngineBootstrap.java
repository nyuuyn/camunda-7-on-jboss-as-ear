package com.example.camunda;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngines;

import javax.annotation.sql.DataSourceDefinition;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Destroyed;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

/**
 * Eagerly bootstraps the engine via CDI lifecycle events (a plain
 * {@code @ApplicationScoped} bean is lazy by default) and runs
 * {@link IdentityBootstrap}. See ADR-6 and ADR-4 in
 * docs/arc42/09-architecture-decisions.md, and
 * docs/arc42/08-crosscutting-concepts.md §8.2/§8.7, for the full rationale.
 */
@ApplicationScoped
@DataSourceDefinition(
        // className must be a DataSource/XADataSource/ConnectionPoolDataSource
        // impl, not a java.sql.Driver - see ADR-4 addendum 1.
        name = "java:app/datasources/ProcessEngine",
        className = "org.h2.jdbcx.JdbcDataSource",
        url = "jdbc:h2:./camunda-h2-database/process-engine;AUTO_SERVER=TRUE",
        user = "sa",
        password = "sa",
        minPoolSize = 1,
        maxPoolSize = 5
)
public class CamundaEngineBootstrap {

    @Inject
    IdentityBootstrap identityBootstrap;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object init) {
        ProcessEngines.init();
        identityBootstrap.run(ProcessEngines.getDefaultProcessEngine());
    }

    void onStop(@Observes @Destroyed(ApplicationScoped.class) Object destroyed) {
        ProcessEngines.destroy();
    }

    @Produces
    @ApplicationScoped
    public ProcessEngine processEngine() {
        return ProcessEngines.getDefaultProcessEngine();
    }
}
