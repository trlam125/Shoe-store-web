package com.example.lshoestore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Component
public class AiServiceAutoStarter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiServiceAutoStarter.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final boolean enabled;
    private final URI aiServiceUri;
    private final int startupTimeoutSeconds;

    private volatile Process aiServiceProcess;

    public AiServiceAutoStarter(
            @Value("${ai.service.autostart:true}") boolean enabled,
            @Value("${ai.service.url:http://localhost:8001}") String aiServiceUrl,
            @Value("${ai.service.autostart.timeout-seconds:60}") int startupTimeoutSeconds) {
        this.enabled = enabled;
        this.aiServiceUri = URI.create(aiServiceUrl);
        this.startupTimeoutSeconds = Math.max(startupTimeoutSeconds, 1);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAiService() {
        if (!enabled) {
            log.info("AI service auto-start is disabled");
            return;
        }
        if (!isLocalService()) {
            log.info("AI service uses a remote URL; auto-start skipped: {}", aiServiceUri);
            return;
        }
        if (isServiceReachable()) {
            log.info("AI service is already running at {}", aiServiceUri);
            return;
        }

        Path serviceDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve("ai-service");
        Path script = resolveStartScript(serviceDirectory);
        if (script == null) {
            log.error("Cannot auto-start AI service: run script not found in {}", serviceDirectory);
            return;
        }

        try {
            ProcessBuilder processBuilder = createProcessBuilder(script, serviceDirectory);
            processBuilder.environment().put("AI_RELOAD", "false");
            processBuilder.environment().put("AI_PORT", String.valueOf(resolvePort()));
            processBuilder.inheritIO();
            aiServiceProcess = processBuilder.start();
            log.info("Starting AI service from {}", script);
            Thread.ofVirtual().name("ai-service-readiness").start(this::waitUntilReady);
        } catch (IOException exception) {
            log.error("Failed to start AI service", exception);
        }
    }

    private boolean isLocalService() {
        String host = aiServiceUri.getHost();
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private int resolvePort() {
        if (aiServiceUri.getPort() > 0) {
            return aiServiceUri.getPort();
        }
        return "https".equalsIgnoreCase(aiServiceUri.getScheme()) ? 443 : 80;
    }

    private Path resolveStartScript(Path serviceDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path script = serviceDirectory.resolve(windows ? "run.bat" : "run.sh");
        return Files.isRegularFile(script) ? script : null;
    }

    private ProcessBuilder createProcessBuilder(Path script, Path serviceDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder processBuilder = windows
                ? new ProcessBuilder("cmd.exe", "/c", script.toString())
                : new ProcessBuilder("sh", script.toString());
        return processBuilder.directory(serviceDirectory.toFile());
    }

    private void waitUntilReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(startupTimeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            Process process = aiServiceProcess;
            if (process == null || !process.isAlive()) {
                log.error("AI service stopped before it became ready");
                return;
            }
            if (isServiceReachable()) {
                log.info("AI service is ready at {}", aiServiceUri);
                return;
            }
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("AI service has not become ready after {} seconds; check the IntelliJ console", startupTimeoutSeconds);
    }

    private boolean isServiceReachable() {
        try {
            String baseUrl = aiServiceUri.toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/openapi.json"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void destroy() {
        Process process = aiServiceProcess;
        if (process == null || !process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        log.info("AI service process stopped");
    }
}
