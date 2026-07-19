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
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class AiServiceAutoStarter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiServiceAutoStarter.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Duration HEALTH_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READINESS_RETRY_DELAY = Duration.ofSeconds(2);

    // Force HTTP/1.1 because Uvicorn does not handle the Java HTTP/2 upgrade probe.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final boolean enabled;
    private final boolean prepareVirtualEnvironment;
    private final String configuredProjectRoot;
    private final URI serviceUri;
    private final int timeoutSeconds;

    private volatile Process environmentSetupProcess;
    private volatile Process aiServiceProcess;
    private volatile boolean shuttingDown;

    public AiServiceAutoStarter(
            @Value("${ai.service.autostart:true}") boolean enabled,
            @Value("${ai.service.setup-venv:true}") boolean prepareVirtualEnvironment,
            @Value("${ai.service.project-root:}") String configuredProjectRoot,
            @Value("${ai.service.url:http://127.0.0.1:8001}") String aiServiceUrl,
            @Value("${ai.service.autostart.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.prepareVirtualEnvironment = prepareVirtualEnvironment;
        this.configuredProjectRoot = configuredProjectRoot == null
                ? ""
                : configuredProjectRoot.trim();
        this.serviceUri = URI.create(aiServiceUrl);
        this.timeoutSeconds = Math.max(timeoutSeconds, 1);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAiService() {
        if (!enabled) {
            log.info("AI service auto-start is disabled");
            return;
        }

        if (!isLocalService(serviceUri)) {
            log.info("AI service uses a remote URL; auto-start skipped: {}", serviceUri);
            return;
        }

        if (endpointReturnsSuccess("/live")) {
            if (endpointReturnsSuccess("/ready")) {
                log.info("AI service is already running and ready at {}", serviceUri);
            } else {
                log.warn("AI service is already running at {} but its database is not ready", serviceUri);
            }
            return;
        }

        Thread.ofVirtual()
                .name("ai-service-startup")
                .start(this::startSafely);
    }

    private void startSafely() {
        try {
            Path projectRoot = resolveProjectRoot();
            log.info("Using LSHOE project root: {}", projectRoot);

            if (shuttingDown) {
                return;
            }

            if (prepareVirtualEnvironment && !prepareVirtualEnvironment(projectRoot)) {
                log.error("AI service was not started because the root virtual environment could not be prepared");
                return;
            }

            if (!shuttingDown) {
                startProcess(projectRoot);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to initialize AI service", exception);
        }
    }

    private boolean prepareVirtualEnvironment(Path projectRoot) {
        Path script = projectRoot.resolve(isWindows() ? "setup-venv.bat" : "setup-venv.sh");
        Path requirements = projectRoot.resolve("requirements.txt");

        if (!Files.isRegularFile(script) || !Files.isRegularFile(requirements)) {
            log.error("Cannot prepare virtual environment: {} or {} is missing", script, requirements);
            return false;
        }

        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder(
                        "cmd.exe", "/d", "/s", "/c",
                        "call \"" + script + "\" \"" + requirements + "\"")
                : new ProcessBuilder("sh", script.toString(), requirements.toString());

        builder.directory(projectRoot.toFile());
        builder.inheritIO();

        try {
            log.info("Preparing Python virtual environment at {}", projectRoot.resolve(".venv"));
            environmentSetupProcess = builder.start();
            int exitCode = environmentSetupProcess.waitFor();
            environmentSetupProcess = null;

            if (exitCode != 0) {
                log.error("Virtual environment setup failed with exit code {}", exitCode);
                return false;
            }
            return true;
        } catch (IOException exception) {
            log.error("Failed to run virtual environment setup", exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startProcess(Path projectRoot) {
        Path serviceDirectory = projectRoot.resolve("ai-service");
        Path script = serviceDirectory.resolve(isWindows() ? "run.bat" : "run.sh");

        if (!Files.isRegularFile(script)) {
            log.error("Cannot auto-start AI service: run script not found at {}", script);
            return;
        }

        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", "call \"" + script + "\"")
                : new ProcessBuilder("sh", script.toString());

        builder.directory(serviceDirectory.toFile());
        builder.environment().put("LSHOE_SKIP_VENV_SETUP", "true");
        builder.environment().put("AI_PORT", String.valueOf(resolvePort(serviceUri)));
        builder.environment().put("AI_RELOAD", "false");
        builder.inheritIO();

        try {
            aiServiceProcess = builder.start();
            log.info("Starting AI service from {}", script);
            Thread.ofVirtual()
                    .name("ai-service-readiness")
                    .start(() -> waitUntilReady(aiServiceProcess));
        } catch (IOException exception) {
            log.error("Failed to start AI service", exception);
        }
    }

    private Path resolveProjectRoot() {
        if (!configuredProjectRoot.isBlank()) {
            Path root = Path.of(configuredProjectRoot).toAbsolutePath().normalize();
            if (isProjectRoot(root)) {
                return root;
            }
            throw new IllegalStateException(
                    "Configured project root is invalid: " + root
                            + ". It must contain pom.xml and the ai-service directory."
            );
        }

        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (isProjectRoot(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Cannot locate the project root from " + current
                        + ". Set LSHOE_PROJECT_ROOT or ai.service.project-root."
        );
    }

    private boolean isProjectRoot(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("ai-service"));
    }

    private boolean isLocalService(URI uri) {
        String host = uri.getHost();
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void waitUntilReady(Process process) {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        boolean liveMessageWritten = false;

        while (!shuttingDown && System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                log.error("AI service stopped before it became ready (exit code {})", process.exitValue());
                return;
            }

            if (endpointReturnsSuccess("/ready")) {
                log.info("AI service is ready at {}", serviceUri);
                return;
            }

            if (!liveMessageWritten && endpointReturnsSuccess("/live")) {
                log.info("AI service process is running at {}; waiting for PostgreSQL readiness", serviceUri);
                liveMessageWritten = true;
            }

            try {
                Thread.sleep(READINESS_RETRY_DELAY);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!shuttingDown) {
            log.warn(
                    "AI service is running but did not become ready after {} seconds. "
                            + "Check AI_DATABASE_URL/DB settings and PostgreSQL.",
                    timeoutSeconds
            );
        }
    }

    private boolean endpointReturnsSuccess(String endpoint) {
        try {
            String baseUrl = serviceUri.toString().replaceAll("/+$", "");
            String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + normalizedEndpoint))
                    .timeout(HEALTH_REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    @Override
    public void destroy() {
        shuttingDown = true;
        stopProcess("virtual environment setup", environmentSetupProcess);
        stopProcess("AI service", aiServiceProcess);
    }

    private void stopProcess(String name, Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        var descendants = process.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();

        descendants.forEach(ProcessHandle::destroy);
        process.destroy();

        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                descendants.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            descendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        log.info("{} process stopped", name);
    }
}
