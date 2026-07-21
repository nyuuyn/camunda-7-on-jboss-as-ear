package com.example.camunda.it;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots a WildFly container (see docker/Dockerfile - a freely-pullable
 * stand-in for JBoss EAP 7.4, with the ProcessEngine datasource baked in),
 * deploys ear/target/camunda-demo.ear onto it, and drives the demo process
 * end-to-end purely over REST/docker-exec - the same black-box perspective
 * an operator would have, not by sharing any classes with the deployed app.
 *
 * Every endpoint, log message, and CLI output format asserted on below was
 * confirmed against a real container run during development, not guessed
 * from documentation - including two real bugs this process caught before
 * they were ever written down as "known limitations": a nonexistent
 * Camunda class referenced in web.xml, and WildFly's built-in JSON-B
 * provider silently outcompeting Jackson and breaking the External Task
 * Client's date parsing. Both fixes live in camunda-engine/ear now; see
 * their commit history / comments for details.
 */
@Testcontainers
class CamundaEarIT {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);

    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("camunda-demo-wildfly-test", false)
            .withFileFromClasspath("Dockerfile", "docker/Dockerfile")
            .withFileFromClasspath("add-datasource.cli", "docker/add-datasource.cli");

    @Container
    static GenericContainer<?> jboss = new GenericContainer<>(IMAGE)
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(System.getProperty("ear.file")),
                    "/opt/jboss/wildfly/standalone/deployments/camunda-demo.ear")
            .waitingFor(Wait.forHttp("/camunda-engine/engine-rest/engine")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    private static String baseUrl() {
        return "http://" + jboss.getHost() + ":" + jboss.getMappedPort(8080) + "/camunda-engine/engine-rest";
    }

    @Test
    void demoProcessCompletesEndToEnd() throws Exception {
        waitForProcessDefinitionDeployed();

        String processInstanceId = startProcessInstance();

        String history = pollUntil(
                () -> get(baseUrl() + "/history/process-instance/" + processInstanceId),
                body -> body.contains("\"endTime\":\"") && !body.contains("\"endTime\":null"),
                "process instance " + processInstanceId + " to complete (endTime set)");

        assertTrue(history.contains("\"state\":\"COMPLETED\""),
                "expected COMPLETED state, got: " + history);
    }

    @Test
    void jobExecutorRunsOnJBossManagedThreadPool() throws Exception {
        waitForProcessDefinitionDeployed();

        int completedBefore = readCompletedTaskCount();

        String processInstanceId = startProcessInstance();
        pollUntil(
                () -> get(baseUrl() + "/history/process-instance/" + processInstanceId),
                body -> body.contains("\"endTime\":\"") && !body.contains("\"endTime\":null"),
                "process instance " + processInstanceId + " to complete");

        // completed-task-count is a supporting signal only - it's the whole
        // shared pool's counter, and DemoProcessDeployer's own REST call
        // also runs on it, so an increase alone doesn't prove *this*
        // process instance's job ran there. The real proof is below:
        // correlating the specific job id for *this* instance (from
        // Camunda's own History REST API, independent of anything we log
        // ourselves) with ManagedJobExecutor's thread-name log line for
        // that exact job id.
        int completedAfter = readCompletedTaskCount();
        assertTrue(completedAfter > completedBefore,
                "expected java:jboss/ee/concurrency/executor/default's completed-task-count to increase "
                        + "(was " + completedBefore + ", now " + completedAfter + ")");

        String jobId = fetchJobIdForProcessInstance(processInstanceId);
        String logs = jboss.getLogs();
        Pattern jobRanOnManagedThread = Pattern.compile(
                "Executing job\\(s\\) \\[[^]]*\\b" + Pattern.quote(jobId) + "\\b[^]]*] "
                        + "on thread \\[EE-ManagedExecutorService-default-Thread-\\d+]");
        assertTrue(jobRanOnManagedThread.matcher(logs).find(),
                "expected a log line showing job " + jobId + " (the async continuation for process instance "
                        + processInstanceId + ", per /history/job-log) executing on an "
                        + "EE-ManagedExecutorService-default thread - without this, the completed-task-count "
                        + "increase above could just be coincidental unrelated activity on the shared pool "
                        + "(e.g. DemoProcessDeployer's own REST call)");
    }

    /**
     * Looks up the async-continuation job Camunda's engine itself created
     * for this process instance (see demo-process.bpmn's
     * camunda:asyncBefore) via the History REST API - an independent
     * source of truth we don't control, unlike anything we log ourselves.
     */
    private String fetchJobIdForProcessInstance(String processInstanceId) {
        String jobLog = get(baseUrl() + "/history/job-log?processInstanceId=" + processInstanceId);
        Matcher matcher = Pattern.compile("\"jobId\":\"([^\"]+)\"").matcher(jobLog);
        if (!matcher.find()) {
            fail("No job log entries found for process instance " + processInstanceId + ": " + jobLog);
        }
        return matcher.group(1);
    }

    private void waitForProcessDefinitionDeployed() throws Exception {
        pollUntil(
                () -> getStatusCode(baseUrl() + "/process-definition/key/demoProcess"),
                status -> status == 200,
                "DemoProcessDeployer to deploy demoProcess via REST");
    }

    private String startProcessInstance() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/process-definition/key/demoProcess/start"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            fail("Failed to start process instance: HTTP " + response.statusCode() + " " + response.body());
        }
        Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(response.body());
        if (!matcher.find()) {
            fail("No id in start response: " + response.body());
        }
        return matcher.group(1);
    }

    private int readCompletedTaskCount() throws IOException, InterruptedException {
        org.testcontainers.containers.Container.ExecResult result = jboss.execInContainer(
                "/opt/jboss/wildfly/bin/jboss-cli.sh", "--connect",
                "--command=/subsystem=ee/managed-executor-service=default:read-resource(include-runtime=true)");
        Matcher matcher = Pattern.compile("\"completed-task-count\"\\s*=>\\s*(\\d+)L").matcher(result.getStdout());
        if (!matcher.find()) {
            fail("Could not find completed-task-count in CLI output: " + result.getStdout());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private static int getStatusCode(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    private static <T> T pollUntil(java.util.function.Supplier<T> supplier,
                                    java.util.function.Predicate<T> condition,
                                    String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = supplier.get();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(1000);
        }
        fail("Timed out after " + POLL_TIMEOUT + " waiting for " + description + " - last value: " + last);
        return last;
    }
}
