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
 * Starts an ngrok HTTP endpoint after the local Spring Boot application is ready.
 *
 * The ngrok authtoken is intentionally not read from the project. Configure it once
 * with: ngrok config add-authtoken YOUR_TOKEN
 */
@Component
public class NgrokTunnelAutoStarter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NgrokTunnelAutoStarter.class);
    private static final Pattern NGROK_PUBLIC_URL = Pattern.compile(
            "https://[a-zA-Z0-9][a-zA-Z0-9.-]*\\.(?:ngrok-free\\.app|ngrok\\.app|ngrok\\.dev)"
    );
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private final boolean enabled;
    private final String configuredProjectRoot;
    private final String executableName;
    private final String configuredLocalUrl;
    private final String configuredPublicUrl;
    private final boolean inspectTraffic;
    private final int serverPort;
    private final int startupTimeoutSeconds;

    private volatile Process ngrokProcess;
    private volatile boolean shuttingDown;

    public NgrokTunnelAutoStarter(
            @Value("${NGROK_TUNNEL_AUTOSTART:${ngrok.tunnel.autostart:false}}") boolean enabled,
            @Value("${LSHOE_PROJECT_ROOT:${ai.service.project-root:}}") String configuredProjectRoot,
            @Value("${NGROK_TUNNEL_EXECUTABLE:${ngrok.tunnel.executable:ngrok.exe}}") String executableName,
            @Value("${NGROK_TUNNEL_LOCAL_URL:${ngrok.tunnel.local-url:}}") String configuredLocalUrl,
            @Value("${NGROK_PUBLIC_URL:${ngrok.tunnel.public-url:}}") String configuredPublicUrl,
            @Value("${NGROK_INSPECT:${ngrok.tunnel.inspect:false}}") boolean inspectTraffic,
            @Value("${SERVER_PORT:${server.port:8081}}") int serverPort,
            @Value("${NGROK_TUNNEL_STARTUP_TIMEOUT_SECONDS:${ngrok.tunnel.startup-timeout-seconds:45}}")
            int startupTimeoutSeconds) {
        this.enabled = enabled;
        this.configuredProjectRoot = normalize(configuredProjectRoot, "");
        this.executableName = normalize(executableName, "ngrok.exe");
        this.configuredLocalUrl = normalize(configuredLocalUrl, "");
        this.configuredPublicUrl = normalize(configuredPublicUrl, "");
        this.inspectTraffic = inspectTraffic;
        this.serverPort = serverPort;
        this.startupTimeoutSeconds = Math.max(startupTimeoutSeconds, 1);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTunnelAfterApplicationReady() {
        if (!enabled) {
            log.info("ngrok Tunnel auto-start is disabled");
            return;
        }

        if (!isWindows()) {
            log.warn("The configured ngrok auto-starter targets Windows; skipping on {}",
                    System.getProperty("os.name"));
            return;
        }

        Thread.ofVirtual()
                .name("ngrok-tunnel-startup")
                .start(this::startSafely);
    }

    private void startSafely() {
        try {
            Path projectRoot = resolveProjectRoot();
            URI localUri = resolveLocalUri();
            URI publicUri = resolvePublicUri();

            if (!waitForLocalApplication(localUri)) {
                log.error("ngrok was not started because the local web application did not become reachable at {} "
                                + "after {} seconds",
                        localUri, startupTimeoutSeconds);
                return;
            }

            if (shuttingDown) {
                return;
            }

            startNgrok(projectRoot, localUri, publicUri);
        } catch (RuntimeException exception) {
            log.error("Failed to initialize ngrok", exception);
        }
    }

    private boolean waitForLocalApplication(URI localUri) {
        long deadline = System.nanoTime() + Duration.ofSeconds(startupTimeoutSeconds).toNanos();
        log.info("Waiting for local web application at {} before starting ngrok", localUri);

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
            return response.statusCode() >= 100 && response.statusCode() < 500;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startNgrok(Path projectRoot, URI localUri, URI publicUri) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(projectRoot));
        command.add("http");
        command.add(toNgrokLocalTarget(localUri));
        command.add("--inspect=" + inspectTraffic);

        if (publicUri != null) {
            command.add("--url");
            command.add(publicUri.toString());
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);

        try {
            ngrokProcess = builder.start();
            if (publicUri == null) {
                log.warn("NGROK_PUBLIC_URL is empty. ngrok will use an endpoint assigned by the account, but "
                        + "password-reset links need NGROK_PUBLIC_URL or APP_PUBLIC_BASE_URL to be fixed.");
                log.info("Starting ngrok with the account's assigned endpoint");
            } else {
                log.info("Starting ngrok at {}", publicUri);
            }
            log.info("ngrok forwards public HTTPS traffic to {}", localUri);

            Process startedProcess = ngrokProcess;
            Thread.ofVirtual()
                    .name("ngrok-tunnel-output")
                    .start(() -> relayOutput(startedProcess));
            Thread.ofVirtual()
                    .name("ngrok-tunnel-monitor")
                    .start(() -> monitorProcess(startedProcess));
        } catch (IOException exception) {
            log.error("Failed to start ngrok. Install ngrok or set NGROK_TUNNEL_EXECUTABLE to ngrok.exe's "
                    + "absolute path.", exception);
        }
    }

    private String resolveExecutable(Path projectRoot) {
        Path configuredPath = Path.of(executableName);
        if (configuredPath.isAbsolute()) {
            if (!Files.isRegularFile(configuredPath)) {
                throw new IllegalStateException("ngrok executable not found at " + configuredPath);
            }
            return configuredPath.normalize().toString();
        }

        Path projectExecutable = projectRoot.resolve(configuredPath).normalize();
        if (Files.isRegularFile(projectExecutable)) {
            return projectExecutable.toString();
        }

        // Let Windows resolve ngrok.exe from PATH when installed with the official setup.
        return executableName;
    }

    private String toNgrokLocalTarget(URI localUri) {
        int port = localUri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(localUri.getScheme()) ? 443 : 80;
        }
        return localUri.getHost() + ":" + port;
    }

    private void relayOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[ngrok] {}", line);

                Matcher matcher = NGROK_PUBLIC_URL.matcher(line);
                if (matcher.find()) {
                    log.info("ngrok public URL: {}", matcher.group());
                }
            }
        } catch (IOException exception) {
            if (!shuttingDown) {
                log.warn("Stopped reading ngrok output", exception);
            }
        }
    }

    private void monitorProcess(Process process) {
        try {
            int exitCode = process.waitFor();
            if (!shuttingDown) {
                log.warn("ngrok stopped with exit code {}. Check whether the authtoken is configured and the "
                        + "NGROK_PUBLIC_URL belongs to this account.", exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private URI resolveLocalUri() {
        String url = configuredLocalUrl.isBlank()
                ? "http://127.0.0.1:" + serverPort
                : configuredLocalUrl;
        URI uri = URI.create(url.replaceAll("/+$", ""));
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || !isRootPath(uri)) {
            throw new IllegalStateException("Invalid NGROK_TUNNEL_LOCAL_URL: " + url);
        }
        return uri;
    }

    private URI resolvePublicUri() {
        if (configuredPublicUrl.isBlank()) {
            return null;
        }

        URI uri = URI.create(configuredPublicUrl.replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !isRootPath(uri)) {
            throw new IllegalStateException("NGROK_PUBLIC_URL must be a root HTTPS URL, for example "
                    + "https://example.ngrok-free.app");
        }
        return uri;
    }

    private boolean isRootPath(URI uri) {
        return uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath());
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
        stopProcess(ngrokProcess);
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

        log.info("ngrok process stopped");
    }
}
