package com.example.camunda.rest;

import org.camunda.bpm.engine.rest.impl.CamundaRestResources;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * Embeds camunda-engine-rest into this WAR's own JAX-RS deployment instead
 * of deploying Camunda's separate engine-rest.war. See ADR-8 in
 * docs/arc42/09-architecture-decisions.md and
 * docs/arc42/08-crosscutting-concepts.md §8.4.
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
