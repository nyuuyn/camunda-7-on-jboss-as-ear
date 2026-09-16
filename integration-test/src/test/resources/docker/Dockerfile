# WildFly 26.1.3.Final is the freely-pullable stand-in for JBoss EAP 7.4
# used by this project's integration tests (EAP images require a Red Hat
# subscription + registry.redhat.io login). Real EAP 7.4 officially
# supports running on JDK 17 (added in a later cumulative patch), and this
# project targets Java 17 - but quay.io never published a
# wildfly:26.1.3.Final-jdk17 tag; its jdk17 tags only start at WildFly 28,
# which is Jakarta EE 10 (jakarta.* namespace) - see
# docs/arc42/02-architecture-constraints.md for why this project stays
# below that.
#
# So this stage takes the already-installed $JBOSS_HOME out of quay.io's
# jdk11-tagged image (a normal, fast registry pull) and copies it onto a
# JDK 17 base instead. Downloading the WildFly release tarball straight
# from GitHub Releases (the approach the upstream wildfly/wildfly-container
# Dockerfile uses) was tried first and abandoned: that CDN throttled to
# ~20 KB/s in testing here, turning a ~200MB download into hours, while
# quay.io pulls of the same WildFly install stay fast.
#
# BASE_IMAGE must be declared here, before the first FROM, to be visible
# to the "FROM ${BASE_IMAGE}" instruction below - an ARG declared inside a
# build stage is scoped to that stage only and isn't visible to a later
# FROM's own variable substitution.
ARG BASE_IMAGE=wildfly-jdk17

FROM quay.io/wildfly/wildfly:26.1.3.Final-jdk11 AS wildfly-dist

FROM eclipse-temurin:17-centos7 AS wildfly-jdk17

WORKDIR /opt/jboss

RUN groupadd -r jboss -g 1000 && useradd -u 1000 -r -g jboss -m -d /opt/jboss -s /sbin/nologin -c "JBoss user" jboss \
    && chmod 755 /opt/jboss

ENV JBOSS_HOME=/opt/jboss/wildfly

COPY --from=wildfly-dist --chown=jboss:0 /opt/jboss/wildfly /opt/jboss/wildfly
RUN chmod -R g+rw ${JBOSS_HOME}

# WORKDIR must be under $JBOSS_HOME (not /opt/jboss, which stays root-owned
# above) - standalone.sh launches java without cd'ing anywhere else, so the
# JVM's user.dir is whatever this is, and CamundaEngineBootstrap's
# @DataSourceDefinition uses a relative H2 file URL
# ("./camunda-h2-database/...") that resolves against it. A real EAP host
# normally launches standalone.sh from within $JBOSS_HOME too (interactively
# from bin/, or via a service unit's WorkingDirectory), which the service
# account owns - this just makes the test image match that instead of
# Docker's default WORKDIR-is-the-image-root-owned-dir behavior (found by
# deploying and hitting "Error while creating file /opt/jboss/camunda-h2-database").
WORKDIR ${JBOSS_HOME}

ENV LAUNCH_JBOSS_IN_BACKGROUND=true

USER jboss

# Override BASE_IMAGE to point at a real EAP image if you have access to
# one - real EAP images already ship JBOSS_HOME fully set up, so pointing
# this at one skips the wildfly-jdk17 stage above entirely (Docker only
# builds stages that are actually referenced).
FROM ${BASE_IMAGE}

# No datasource-baking step needed here anymore: the "ProcessEngine"
# datasource is declared inside the EAR itself via @DataSourceDefinition
# (see camunda-engine's CamundaEngineBootstrap), so it deploys/undeploys
# with the EAR. All this image needs is the JDBC driver WildFly already
# ships for its own default ExampleDS (module com.h2database.h2,
# jdbc-driver=h2, confirmed present in a fresh container) - nothing to
# install. The EAR itself is still copied in at container start (see
# CamundaEarIT), not baked into the image.

# -b 0.0.0.0: the base image's public interface defaults to 127.0.0.1,
# unreachable from outside the container.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
