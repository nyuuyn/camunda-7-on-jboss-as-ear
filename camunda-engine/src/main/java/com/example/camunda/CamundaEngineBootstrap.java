package com.example.camunda;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngines;

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
 */
@ApplicationScoped
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
