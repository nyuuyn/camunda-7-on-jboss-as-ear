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
 * Boots a WildFly container (see docker/Dockerfile, ADR-7), deploys
 * ear/target/camunda-demo.ear onto it, and drives the demo process
 * end-to-end purely over REST/docker-exec - the same black-box perspective
 * an operator would have. See docs/arc42/10-quality-requirements.md and
 * docs/arc42/11-risks-and-technical-debt.md §11.1 for what's checked here
 * and why.
 */
@Testcontainers
class CamundaEarIT {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);

    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("camunda-demo-wildfly-test", false)
            .withFileFromClasspath("Dockerfile", "docker/Dockerfile");

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

    private static String webUiBaseUrl() {
        return "http://" + jboss.getHost() + ":" + jboss.getMappedPort(8080) + "/camunda-web-ui";
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
    void identityBootstrapCreatesExampleUsersAndGroups() throws Exception {
        // Runs synchronously before the webapp finishes starting, and the
        // container Wait strategy above already blocks on /engine-rest/engine
        // responding - so bootstrap has necessarily already run; no polling needed.
        assertUserExists("admin");
        assertUserExists("support");
        assertUserExists("readonly");

        assertGroupMembership("admin", "camunda-admin");
        assertGroupMembership("support", "support");
        assertGroupMembership("readonly", "readonly");

        String adminAuthorizations = get(baseUrl() + "/authorization?groupIdIn=camunda-admin");
        assertTrue(adminAuthorizations.contains("\"groupId\":\"camunda-admin\""),
                "expected IdentityBootstrap to have granted the camunda-admin group an authorization, got: "
                        + adminAuthorizations);
    }

    /**
     * Proves camunda-web-ui.war resolves the same shared ProcessEngine
     * CamundaEngineBootstrap populated, via a real login against the seeded
     * admin user. See docs/arc42/05-building-block-view.md §5.4.
     */
    @Test
    void webUiLoginWorksAgainstSharedEngine() throws Exception {
        HttpRequest welcomePage = HttpRequest.newBuilder()
                .uri(URI.create(webUiBaseUrl() + "/app/welcome/default/"))
                .GET()
                .build();
        HttpResponse<String> welcomeResponse = HTTP_CLIENT.send(welcomePage, HttpResponse.BodyHandlers.ofString());
        // CsrfPreventionFilter ties the XSRF token to the session (JSESSIONID),
        // so every cookie from the welcome page must be replayed, not just XSRF-TOKEN.
        java.util.List<String> setCookies = welcomeResponse.headers().allValues("Set-Cookie");
        String cookieHeader = setCookies.stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .reduce((a, b) -> a + "; " + b)
                .orElseThrow(() -> new AssertionError("No cookies in welcome page response ("
                        + welcomeResponse.statusCode() + "): " + welcomeResponse.headers()));
        String xsrfToken = setCookies.stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .findFirst()
                .map(cookie -> cookie.substring("XSRF-TOKEN=".length()))
                .orElseThrow(() -> new AssertionError("No XSRF-TOKEN cookie in welcome page response ("
                        + welcomeResponse.statusCode() + "): " + welcomeResponse.headers()));

        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(webUiBaseUrl() + "/api/admin/auth/user/default/login/welcome"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader)
                .header("X-XSRF-TOKEN", xsrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin"))
                .build();
        HttpResponse<String> loginResponse = HTTP_CLIENT.send(login, HttpResponse.BodyHandlers.ofString());

        assertTrue(loginResponse.statusCode() / 100 == 2,
                "expected the seeded admin user to log into camunda-web-ui, got HTTP "
                        + loginResponse.statusCode() + ": " + loginResponse.body());
        assertTrue(loginResponse.body().contains("\"cockpit\""),
                "expected admin to be authorized for cockpit (via IdentityBootstrap's camunda-admin group "
                        + "APPLICATION grant), got: " + loginResponse.body());
    }

    private void assertUserExists(String userId) {
        String body = get(baseUrl() + "/user?id=" + userId);
        assertTrue(body.contains("\"id\":\"" + userId + "\""),
                "expected IdentityBootstrap to have created user '" + userId + "', got: " + body);
    }

    private void assertGroupMembership(String userId, String groupId) {
        String body = get(baseUrl() + "/group?id=" + groupId + "&member=" + userId);
        assertTrue(body.contains("\"id\":\"" + groupId + "\""),
                "expected IdentityBootstrap to have made '" + userId + "' a member of group '" + groupId
                        + "', got: " + body);
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

        // completed-task-count is a supporting signal only (the shared pool's
        // counter, not proof this instance's job ran there); the real proof
        // is the job-id/thread-name correlation below. See arc42 §11.4.
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
                        + "EE-ManagedExecutorService-default thread");
    }

    /** Looks up the async-continuation job for this instance via the History REST API. */
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
