package com.example.camunda.rest;

import org.camunda.bpm.engine.rest.impl.CamundaRestResources;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * Embeds camunda-engine-rest into this WAR's own JAX-RS deployment
 * (RESTEasy, built into JBoss EAP) rather than deploying Camunda's
 * separate engine-rest.war. Exposes the full REST API - deployments,
 * process definitions/instances, external tasks, etc. - under
 * /engine-rest, e.g. POST /camunda-engine/engine-rest/deployment/create.
 *
 * Which ProcessEngine the REST resources operate on is resolved by
 * ContainerManagedProcessEngineProvider (see
 * META-INF/services/org.camunda.bpm.engine.rest.spi.ProcessEngineProvider),
 * which falls back to the plain org.camunda.bpm.engine.ProcessEngines
 * registry when there's no subsystem/BpmPlatform involved - exactly our
 * situation, since the engine is bootstrapped from camunda.cfg.xml.
 */
@ApplicationPath("/engine-rest")
public class CamundaRestApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> classes = new HashSet<>();
        classes.addAll(CamundaRestResources.getResourceClasses());
        classes.addAll(CamundaRestResources.getConfigurationClasses());
        return classes;
    }
}
