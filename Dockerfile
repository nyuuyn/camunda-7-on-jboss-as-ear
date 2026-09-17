# syntax=docker/dockerfile:1
#
# Self-contained image: `docker build -t camunda-demo .` then
# `docker run -p 8080:8080 camunda-demo` - no local JDK/Maven/JBoss needed.
# See docs/arc42/07-deployment-view.md §7.4 for how this differs from
# integration-test/src/test/resources/docker/Dockerfile (§7.2).
ARG BASE_IMAGE=wildfly-jdk17

# ---- Build stage: compile the EAR ----
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY . .

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -pl ear -am package

# ---- Runtime stage: WildFly with the EAR baked in ----
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

# Overridable to a real EAP image - see arc42 §7.4.
FROM ${BASE_IMAGE}

COPY --from=build --chown=jboss:root /build/ear/target/camunda-demo.ear ${JBOSS_HOME}/standalone/deployments/camunda-demo.ear

EXPOSE 8080

# -b 0.0.0.0: the base image's public interface defaults to 127.0.0.1, unreachable from outside the container.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
