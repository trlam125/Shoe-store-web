package com.example.lshoestore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts Cloudflare Tunnel after the local Spring Boot web server is reachable.
 *
 * Supported modes:
 * - quick: creates a temporary *.trycloudflare.com URL.
 * - named: runs a locally managed named tunnel using cloudflared-config.yml.
 */
@Component
public class CloudflareTunnelAutoStarter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CloudflareTunnelAutoStarter.class);
    private static final Pattern QUICK_TUNNEL_URL = Pattern.compile(
            "https://[a-zA-Z0-9-]+\\.trycloudflare\\.com"
    );
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private final boolean enabled;
    private final String mode;
    private final String configuredProjectRoot;
    private final String executableName;
    private final String configuredLocalUrl;
    private final int serverPort;
    private final String configFileName;
    private final String tunnelName;
    private final int startupTimeoutSeconds;

    private volatile Process cloudflaredProcess;
    private volatile boolean shuttingDown;

    public CloudflareTunnelAutoStarter(
            @Value("${CLOUDFLARE_TUNNEL_AUTOSTART:${cloudflare.tunnel.autostart:false}}") boolean enabled,
            @Value("${CLOUDFLARE_TUNNEL_MODE:${cloudflare.tunnel.mode:quick}}") String mode,
            @Value("${LSHOE_PROJECT_ROOT:${ai.service.project-root:}}") String configuredProjectRoot,
            @Value("${CLOUDFLARE_TUNNEL_EXECUTABLE:${cloudflare.tunnel.executable:cloudflared-windows-amd64.exe}}") String executableName,
            @Value("${CLOUDFLARE_TUNNEL_LOCAL_URL:${cloudflare.tunnel.local-url:}}") String configuredLocalUrl,
            @Value("${SERVER_PORT:${server.port:8081}}") int serverPort,
            @Value("${CLOUDFLARE_TUNNEL_CONFIG:${cloudflare.tunnel.config:cloudflared-config.yml}}") String configFileName,
            @Value("${CLOUDFLARE_TUNNEL_NAME:${cloudflare.tunnel.name:lshoe-store}}") String tunnelName,
            @Value("${CLOUDFLARE_TUNNEL_STARTUP_TIMEOUT_SECONDS:${cloudflare.tunnel.startup-timeout-seconds:45}}") int startupTimeoutSeconds) {
        this.enabled = enabled;
        this.mode = normalize(mode, "quick");
        this.configuredProjectRoot = normalize(configuredProjectRoot, "");
        this.executableName = normalize(executableName, "cloudflared-windows-amd64.exe");
        this.configuredLocalUrl = normalize(configuredLocalUrl, "");
        this.serverPort = serverPort;
        this.configFileName = normalize(configFileName, "cloudflared-config.yml");
        this.tunnelName = normalize(tunnelName, "lshoe-store");
        this.startupTimeoutSeconds = Math.max(startupTimeoutSeconds, 1);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTunnelAfterApplicationReady() {
        if (!enabled) {
            log.info("Cloudflare Tunnel auto-start is disabled");
            return;
        }

        if (!isWindows()) {
            log.warn("This Cloudflare auto-starter is configured for cloudflared-windows-amd64.exe; skipping on {}",
                    System.getProperty("os.name"));
            return;
        }

        Thread.ofVirtual()
                .name("cloudflare-tunnel-startup")
                .start(this::startSafely);
    }

    private void startSafely() {
        try {
            Path projectRoot = resolveProjectRoot();
            URI localUri = resolveLocalUri();

            if (!waitForLocalApplication(localUri)) {
                log.error("Cloudflare Tunnel was not started because the local web application did not become "
                                + "reachable at {} after {} seconds",
                        localUri, startupTimeoutSeconds);
                return;
            }

            if (shuttingDown) {
                return;
            }

            startCloudflared(projectRoot, localUri);
        } catch (RuntimeException exception) {
            log.error("Failed to initialize Cloudflare Tunnel", exception);
        }
    }

    private boolean waitForLocalApplication(URI localUri) {
        long deadline = System.nanoTime() + Duration.ofSeconds(startupTimeoutSeconds).toNanos();
        log.info("Waiting for local web application at {} before starting Cloudflare Tunnel", localUri);

        while (!shuttingDown && System.nanoTime() < deadline) {
            if (isHttpEndpointReachable(localUri)) {
                log.info("Local web application is reachable at {}", localUri);
                return true;
            }

            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isHttpEndpointReachable(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            // A redirect, unauthorized, forbidden, or not-found response still proves that
            // the local HTTP server is alive. Only 5xx is treated as not ready.
            return response.statusCode() >= 100 && response.statusCode() < 500;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startCloudflared(Path projectRoot, URI localUri) {
        Path executable = resolvePath(projectRoot, executableName);
        if (!Files.isRegularFile(executable)) {
            log.error("cloudflared executable not found at {}", executable);
            return;
        }

        List<String> command = buildCommand(projectRoot, executable, localUri);
        if (command.isEmpty()) {
            return;
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);

        try {
            cloudflaredProcess = builder.start();
            log.info("Starting Cloudflare Tunnel in '{}' mode", mode);
            Thread.ofVirtual()
                    .name("cloudflare-tunnel-output")
                    .start(() -> relayOutput(cloudflaredProcess));
            Thread.ofVirtual()
                    .name("cloudflare-tunnel-monitor")
                    .start(() -> monitorProcess(cloudflaredProcess));
        } catch (IOException exception) {
            log.error("Failed to start cloudflared", exception);
        }
    }

    private List<String> buildCommand(Path projectRoot, Path executable, URI localUri) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("tunnel");

        switch (mode) {
            case "quick" -> {
                command.add("--url");
                command.add(localUri.toString());
            }
            case "named" -> {
                Path configFile = resolvePath(projectRoot, configFileName);
                if (!Files.isRegularFile(configFile)) {
                    log.error("Named tunnel config file not found at {}", configFile);
                    return List.of();
                }
                if (tunnelName.isBlank()) {
                    log.error("CLOUDFLARE_TUNNEL_NAME must be set when using named mode");
                    return List.of();
                }

                command.add("--config");
                command.add(configFile.toString());
                command.add("run");
                command.add(tunnelName);
            }
            default -> {
                log.error("Unsupported CLOUDFLARE_TUNNEL_MODE='{}'. Use 'quick' or 'named'.", mode);
                return List.of();
            }
        }
        return command;
    }

    private void relayOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[cloudflared] {}", line);

                Matcher matcher = QUICK_TUNNEL_URL.matcher(line);
                if (matcher.find()) {
                    log.info("Cloudflare public URL: {}", matcher.group());
                }
            }
        } catch (IOException exception) {
            if (!shuttingDown) {
                log.warn("Stopped reading cloudflared output", exception);
            }
        }
    }

    private void monitorProcess(Process process) {
        try {
            int exitCode = process.waitFor();
            if (!shuttingDown) {
                log.warn("cloudflared stopped with exit code {}", exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private URI resolveLocalUri() {
        String url = configuredLocalUrl.isBlank()
                ? "http://127.0.0.1:" + serverPort
                : configuredLocalUrl;
        return URI.create(url.replaceAll("/+$", ""));
    }

    private Path resolveProjectRoot() {
        if (!configuredProjectRoot.isBlank()) {
            Path configured = Path.of(configuredProjectRoot).toAbsolutePath().normalize();
            if (isProjectRoot(configured)) {
                return configured;
            }
            throw new IllegalStateException("Configured LSHOE project root is invalid: " + configured);
        }

        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (isProjectRoot(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Cannot locate the project root from " + current + ". Set LSHOE_PROJECT_ROOT."
        );
    }

    private boolean isProjectRoot(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("src"));
    }

    private Path resolvePath(Path projectRoot, String value) {
        Path path = Path.of(value);
        return path.isAbsolute()
                ? path.normalize()
                : projectRoot.resolve(path).normalize();
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    @Override
    public void destroy() {
        shuttingDown = true;
        stopProcess(cloudflaredProcess);
    }

    private void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        List<ProcessHandle> descendants = process.descendants()
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

        log.info("Cloudflare Tunnel process stopped");
    }
}
