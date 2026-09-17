# WildFly 26.1.3.Final stands in for JBoss EAP 7.4 (ADR-7). $JBOSS_HOME is
# copied from quay.io's jdk11-tagged image onto a JDK 17 base rather than
# downloading the release tarball directly. See
# docs/arc42/07-deployment-view.md §7.2 for the full rationale.
ARG BASE_IMAGE=wildfly-jdk17

FROM quay.io/wildfly/wildfly:26.1.3.Final-jdk11 AS wildfly-dist

FROM eclipse-temurin:17-centos7 AS wildfly-jdk17

WORKDIR /opt/jboss

RUN groupadd -r jboss -g 1000 && useradd -u 1000 -r -g jboss -m -d /opt/jboss -s /sbin/nologin -c "JBoss user" jboss \
    && chmod 755 /opt/jboss

ENV JBOSS_HOME=/opt/jboss/wildfly

COPY --from=wildfly-dist --chown=jboss:0 /opt/jboss/wildfly /opt/jboss/wildfly
RUN chmod -R g+rw ${JBOSS_HOME}

# Must be under $JBOSS_HOME, not /opt/jboss - see arc42 §7.2.
WORKDIR ${JBOSS_HOME}

ENV LAUNCH_JBOSS_IN_BACKGROUND=true

USER jboss

# Overridable to a real EAP image - see arc42 §7.2.
FROM ${BASE_IMAGE}

# No datasource/driver baking needed - both ship inside the EAR itself
# (@DataSourceDefinition). The EAR is copied in at container start by
# CamundaEarIT, not baked into this image.

# -b 0.0.0.0: the base image's public interface defaults to 127.0.0.1, unreachable from outside the container.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
