# WildFly 26.1.3.Final is the freely-pullable stand-in for JBoss EAP 7.4
# used by this project's integration tests (EAP images require a Red Hat
# subscription + registry.redhat.io login). Override BASE_IMAGE to point
# at a real EAP image if you have access to one.
ARG BASE_IMAGE=quay.io/wildfly/wildfly:26.1.3.Final-jdk11
FROM ${BASE_IMAGE}

USER root

# Bakes in the "ProcessEngine" datasource (mirrors
# server-config/add-datasource.cli, reusing the h2 driver WildFly already
# ships for its own default ExampleDS - see add-datasource.cli) so the
# container is ready to deploy the EAR onto as soon as it starts. The EAR
# itself is copied in at container start (see CamundaEarIT), not baked in
# here, so this image layer stays cached across ordinary rebuilds of the EAR.
COPY add-datasource.cli /tmp/add-datasource.cli

RUN ${JBOSS_HOME}/bin/jboss-cli.sh --file=/tmp/add-datasource.cli \
    && rm /tmp/add-datasource.cli \
    && chown -R jboss:root ${JBOSS_HOME}/standalone

USER jboss

# -b 0.0.0.0: the base image's public interface defaults to 127.0.0.1,
# unreachable from outside the container.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
