package com.example.camunda;

import org.camunda.bpm.engine.ProcessEngines;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Triggers ProcessEngines.init() on webapp deployment, which scans the
 * classpath for camunda.cfg.xml (bundled in this WAR's WEB-INF/classes)
 * and builds/registers the "default" engine from it.
 *
 * A previous version of this class referenced
 * org.camunda.bpm.engine.test.impl.servlet.listener.ProcessEnginesServletContextListener,
 * which turned out not to exist in camunda-engine:7.19.0 at all (verified
 * directly against the jar contents after an integration test surfaced a
 * ClassNotFoundException on deployment) - that class name came from an
 * earlier, unverified web search and was wrong. This one-line wrapper
 * around the real, verified ProcessEngines.init()/destroy() API replaces
 * it and removes the dependency on a nonexistent Camunda class entirely.
 */
@WebListener
public class CamundaEngineBootstrapListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ProcessEngines.init();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ProcessEngines.destroy();
    }
}
