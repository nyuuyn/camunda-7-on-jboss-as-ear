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
 * Bootstraps the engine via CDI instead of a ServletContextListener - a
 * plain @ApplicationScoped bean would NOT do this on its own, since CDI
 * beans are lazy by default and nothing would ever look this one up. The
 * @Observes @Initialized(ApplicationScoped.class)/@Destroyed pair is what
 * forces eager instantiation and gives us the same "run once at webapp
 * startup/shutdown" semantics a ServletContextListener would - CDI fires
 * that event once Weld's own listener sees the ServletContext initialize,
 * so the timing is equivalent, just one layer removed.
 *
 * Also exposes the resulting ProcessEngine as an injectable CDI bean
 * (@Produces below), so other beans added to this WAR later can just
 * @Inject ProcessEngine instead of calling ProcessEngines.getDefaultProcessEngine()
 * themselves.
 *
 * Requires WEB-INF/beans.xml for this WAR to be recognized as a CDI bean
 * archive - without it, this class would never be discovered and none of
 * this would fire.
 *
 * Also runs IdentityBootstrap once the engine is up, to create a set of
 * example users/groups/authorizations - see that class for why it has to
 * tolerate concurrent creation across nodes (issue #1). IdentityBootstrap is
 * injected (rather than called statically) so its {@code @Transactional}
 * run() goes through the CDI proxy and is actually intercepted - a direct
 * static/self call bypasses interceptors entirely.
 *
 * {@code @DataSourceDefinition} below is the "ProcessEngine" datasource
 * camunda.cfg.xml's {@code dataSourceJndiName} looks up - declared here,
 * inside the EAR, instead of via a server-side {@code data-source add}
 * (see docs/arc42/09-architecture-decisions.md, ADR-4 addendum). No
 * server-side setup is required for this at all: WildFly resolves this
 * annotation's {@code className} by loading it through camunda-engine.war's
 * own module classloader, and pom.xml ships {@code com.h2database:h2} at
 * {@code runtime} scope specifically so that classloader can find
 * {@code org.h2.jdbcx.JdbcDataSource} straight out of this WAR's own
 * WEB-INF/lib - no server module, no
 * jboss-deployment-structure.xml dependency entry needed (confirmed by
 * deploying to a completely stock WildFly). The name must be one of the
 * four EE-standard JNDI namespaces (comp/module/app/global) - java:app so
 * it's visible
 * EAR-wide, matching the old java:jboss/... name's effective scope.
 */
@ApplicationScoped
@DataSourceDefinition(
        // @DataSourceDefinition.className must be a javax.sql.DataSource/
        // XADataSource/ConnectionPoolDataSource implementation, NOT a
        // java.sql.Driver - org.h2.Driver (what the CLI's driver-class-name
        // wants) fails deployment with WFLYJCA0117 "is not a valid
        // javax.sql.DataSource implementation" (found by deploying).
        // org.h2.jdbcx.JdbcDataSource is H2's DataSource implementation.
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
