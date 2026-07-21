package com.example.camunda;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngines;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Destroyed;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;

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
 */
@ApplicationScoped
public class CamundaEngineBootstrap {

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object init) {
        ProcessEngines.init();
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
