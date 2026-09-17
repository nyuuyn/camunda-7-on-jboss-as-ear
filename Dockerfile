# syntax=docker/dockerfile:1
#
# Builds and runs the whole demo in one image: `docker build -t camunda-demo .`
# then `docker run -p 8080:8080 camunda-demo` - no local JDK/Maven/JBoss
# install needed. This is a separate concern from
# integration-test/src/test/resources/docker/Dockerfile, which Testcontainers
# owns for `mvn verify` (it copies the EAR in per test run to keep its layer
# cache valid across ordinary EAR rebuilds); this Dockerfile bakes the EAR in
# because its whole point is to be immediately runnable on its own.

# BASE_IMAGE must be declared here, before the first FROM, to be visible to
# the "FROM ${BASE_IMAGE}" instruction near the end of this file - an ARG
# declared inside a build stage (i.e. after some other FROM) is scoped to
# that stage only and isn't visible to a later FROM's own substitution.
ARG BASE_IMAGE=wildfly-jdk17

# ---- Build stage: compile the EAR ----
# A plain "mvn clean package" (see README.md) never needs Docker itself -
# this stage just runs that same build inside the image.
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY . .

# -pl ear -am: build only the ear module and what it depends on
# (camunda-engine, process-application, camunda-web-ui) - deliberately
# skips the integration-test module (not a dependency of ear), so this
# stage never pulls in Testcontainers/Docker-in-Docker dependencies just
# to produce the EAR. The cache mount keeps the local Maven repo across
# rebuilds without baking it into any image layer.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -pl ear -am package

# ---- Runtime stage: WildFly with the EAR baked in ----
# Mirrors integration-test/src/test/resources/docker/Dockerfile's base-image
# setup - see that file's comments for the full rationale (no jdk17 tag for
# WildFly 26.1.3.Final on quay.io, so $JBOSS_HOME is copied from the
# jdk11-tagged image onto a JDK 17 base instead of downloading the release
# tarball directly, which throttled badly in testing).
FROM quay.io/wildfly/wildfly:26.1.3.Final-jdk11 AS wildfly-dist

FROM eclipse-temurin:17-centos7 AS wildfly-jdk17

WORKDIR /opt/jboss

RUN groupadd -r jboss -g 1000 && useradd -u 1000 -r -g jboss -m -d /opt/jboss -s /sbin/nologin -c "JBoss user" jboss \
    && chmod 755 /opt/jboss

ENV JBOSS_HOME=/opt/jboss/wildfly

COPY --from=wildfly-dist --chown=jboss:0 /opt/jboss/wildfly /opt/jboss/wildfly
RUN chmod -R g+rw ${JBOSS_HOME}

# Must be under $JBOSS_HOME, not /opt/jboss (which stays root-owned above) -
# CamundaEngineBootstrap's @DataSourceDefinition uses a relative H2 file URL
# ("./camunda-h2-database/...") that resolves against the JVM's user.dir,
# i.e. wherever standalone.sh was launched from (found the hard way while
# building the Testcontainers image - see that Dockerfile's comments).
WORKDIR ${JBOSS_HOME}

ENV LAUNCH_JBOSS_IN_BACKGROUND=true

USER jboss

# Override BASE_IMAGE to point at a real EAP image if you have access to
# one - real EAP images already ship JBOSS_HOME fully set up, so this skips
# the wildfly-jdk17 stage above entirely (Docker only builds stages that
# are actually referenced).
FROM ${BASE_IMAGE}

# The EAR is fully self-contained - its "ProcessEngine" datasource
# (@DataSourceDefinition on CamundaEngineBootstrap) and JDBC driver
# (bundled in camunda-engine.war's own WEB-INF/lib) both ship with it, so
# nothing needs installing on the server first. See
# docs/arc42/09-architecture-decisions.md, ADR-4.
COPY --from=build --chown=jboss:root /build/ear/target/camunda-demo.ear ${JBOSS_HOME}/standalone/deployments/camunda-demo.ear

EXPOSE 8080

# -b 0.0.0.0: the base image's public interface defaults to 127.0.0.1,
# unreachable from outside the container.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
