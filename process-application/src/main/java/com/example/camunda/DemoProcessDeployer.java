package com.example.camunda;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.enterprise.concurrent.ManagedExecutorService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deploys demo-process.bpmn to the engine over REST (POST
 * /deployment/create) instead of via a process archive / @ProcessApplication
 * bundled in the same module as the engine. This module has no compile-time
 * dependency on camunda-engine at all - it only ever talks to the engine
 * over HTTP, which is what actually keeps the process application decoupled
 * from the engine module rather than just organizationally separate.
 *
 * Runs on JBoss's ManagedExecutorService (not a raw Thread) so this stays
 * consistent with the rest of the project's container-thread-pool policy,
 * and retries with backoff since there's no ordering guarantee that
 * camunda-engine's REST API is already up when this singleton starts (see
 * the top-level README's ordering caveat).
 */
@Singleton
@Startup
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class DemoProcessDeployer {

    private static final Logger LOG = Logger.getLogger(DemoProcessDeployer.class.getName());
    private static final String RESOURCE_NAME = "demo-process.bpmn";
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_DELAY_MILLIS = 2000;

    @Resource(lookup = "java:jboss/ee/concurrency/executor/default")
    private ManagedExecutorService executorService;

    @PostConstruct
    public void start() {
        executorService.execute(this::deployWithRetry);
    }

    private void deployWithRetry() {
        String baseUrl = System.getProperty("camunda.engine.rest.baseUrl", "http://localhost:8080/camunda-engine/engine-rest");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                deploy(baseUrl);
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    LOG.log(Level.SEVERE, "Giving up deploying " + RESOURCE_NAME + " to " + baseUrl
                            + " after " + attempt + " attempts", e);
                    return;
                }
                LOG.info("Deployment attempt " + attempt + "/" + MAX_ATTEMPTS + " to " + baseUrl
                        + " failed (" + e + "), retrying in " + RETRY_DELAY_MILLIS + "ms");
                sleep(RETRY_DELAY_MILLIS);
            }
        }
    }

    private void deploy(String baseUrl) throws IOException, InterruptedException {
        byte[] bpmnBytes = readClasspathResource(RESOURCE_NAME);

        String boundary = "----DemoProcessDeployerBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, bpmnBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/deployment/create"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Deployment failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        LOG.info("Deployed " + RESOURCE_NAME + " via REST API: " + response.body());
    }

    private byte[] buildMultipartBody(String boundary, byte[] bpmnBytes) throws IOException {
        String crlf = "\r\n";
        StringBuilder header = new StringBuilder();
        header.append("--").append(boundary).append(crlf);
        header.append("Content-Disposition: form-data; name=\"deployment-name\"").append(crlf).append(crlf);
        header.append("demo-process-deployment").append(crlf);

        header.append("--").append(boundary).append(crlf);
        header.append("Content-Disposition: form-data; name=\"deployment-source\"").append(crlf).append(crlf);
        header.append("process-application-rest-deployer").append(crlf);

        header.append("--").append(boundary).append(crlf);
        header.append("Content-Disposition: form-data; name=\"enable-duplicate-filtering\"").append(crlf).append(crlf);
        header.append("true").append(crlf);

        // The part name IS the deployment resource name; it must end in a
        // recognized extension (.bpmn/.bpmn20.xml) for the engine to parse
        // it as a process definition.
        header.append("--").append(boundary).append(crlf);
        header.append("Content-Disposition: form-data; name=\"").append(RESOURCE_NAME)
                .append("\"; filename=\"").append(RESOURCE_NAME).append("\"").append(crlf);
        header.append("Content-Type: text/xml").append(crlf).append(crlf);

        String footer = crlf + "--" + boundary + "--" + crlf;

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headerBytes.length + bpmnBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(bpmnBytes, 0, result, headerBytes.length, bpmnBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + bpmnBytes.length, footerBytes.length);
        return result;
    }

    private byte[] readClasspathResource(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + name);
            }
            return in.readAllBytes();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
