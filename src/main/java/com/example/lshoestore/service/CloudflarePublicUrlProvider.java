package com.example.lshoestore.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shares a Cloudflare Quick Tunnel URL with security-sensitive link builders.
 *
 * The URL is accepted only from the locally started cloudflared process or from
 * the runtime file written by run-cloudflare.bat. Request headers are never used.
 */
@Service
public class CloudflarePublicUrlProvider {
    private static final Logger log = LoggerFactory.getLogger(CloudflarePublicUrlProvider.class);

    private final AtomicReference<String> currentUrl = new AtomicReference<>("");
    private final Path runtimeFile;

    public CloudflarePublicUrlProvider(
            @Value("${LSHOE_PROJECT_ROOT:${ai.service.project-root:}}") String configuredProjectRoot,
            @Value("${cloudflare.tunnel.runtime-url-file:.runtime/cloudflare-public-url.txt}")
            String runtimeFileName) {
        Path projectRoot = configuredProjectRoot == null || configuredProjectRoot.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(configuredProjectRoot.trim());
        Path configuredFile = Path.of(runtimeFileName.trim());
        this.runtimeFile = (configuredFile.isAbsolute()
                ? configuredFile
                : projectRoot.resolve(configuredFile)).toAbsolutePath().normalize();
    }

    public Optional<String> current() {
        String memoryValue = normalizeQuickTunnelUrl(currentUrl.get());
        if (!memoryValue.isBlank()) return Optional.of(memoryValue);

        try {
            if (!Files.isRegularFile(runtimeFile)) return Optional.empty();
            String fileValue = normalizeQuickTunnelUrl(Files.readString(runtimeFile, StandardCharsets.UTF_8));
            if (fileValue.isBlank()) return Optional.empty();
            return Optional.of(fileValue);
        } catch (IOException exception) {
            log.warn("Cannot read Cloudflare runtime URL from {}", runtimeFile, exception);
            return Optional.empty();
        }
    }

    public void publish(String value) {
        String normalized = normalizeQuickTunnelUrl(value);
        if (normalized.isBlank()) {
            log.warn("Ignored invalid Cloudflare Quick Tunnel URL: {}", value);
            return;
        }
        currentUrl.set(normalized);
        try {
            Path parent = runtimeFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = runtimeFile.resolveSibling(runtimeFile.getFileName() + ".tmp");
            Files.writeString(temporary, normalized + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, runtimeFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, runtimeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            log.warn("Cannot persist Cloudflare runtime URL to {}", runtimeFile, exception);
        }
    }

    public void clear(String expectedUrl) {
        String expected = normalizeQuickTunnelUrl(expectedUrl);
        if (expected.isBlank()) return;
        currentUrl.updateAndGet(current ->
                expected.equals(normalizeQuickTunnelUrl(current)) ? "" : current);
        try {
            if (!Files.isRegularFile(runtimeFile)) return;
            String fileValue = normalizeQuickTunnelUrl(Files.readString(runtimeFile, StandardCharsets.UTF_8));
            if (expected.equals(fileValue)) Files.deleteIfExists(runtimeFile);
        } catch (IOException exception) {
            log.warn("Cannot clear Cloudflare runtime URL at {}", runtimeFile, exception);
        }
    }

    public void clearAll() {
        currentUrl.set("");
        try {
            Files.deleteIfExists(runtimeFile);
        } catch (IOException exception) {
            log.warn("Cannot clear stale Cloudflare runtime URL at {}", runtimeFile, exception);
        }
    }

    private String normalizeQuickTunnelUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim().replaceAll("/+$", ""));
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !host.toLowerCase().endsWith(".trycloudflare.com")
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
                return "";
            }
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }
}
