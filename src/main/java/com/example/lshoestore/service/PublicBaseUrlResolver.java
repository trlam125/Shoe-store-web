package com.example.lshoestore.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

@Service
public class PublicBaseUrlResolver {
    private final String configuredBaseUrl;
    private final String configuredTunnelUrl;
    private final boolean development;
    private final CloudflarePublicUrlProvider cloudflarePublicUrlProvider;

    public PublicBaseUrlResolver(@Value("${app.public-base-url:}") String configuredBaseUrl,
                                 @Value("${ngrok.tunnel.public-url:}") String configuredTunnelUrl,
                                 @Value("${app.environment:production}") String environment,
                                 CloudflarePublicUrlProvider cloudflarePublicUrlProvider) {
        this.configuredBaseUrl = normalizeConfigured(configuredBaseUrl);
        this.configuredTunnelUrl = normalizeConfigured(configuredTunnelUrl);
        this.development = "development".equalsIgnoreCase(environment);
        this.cloudflarePublicUrlProvider = cloudflarePublicUrlProvider;
    }

    public String resolve(HttpServletRequest request) {
        // Never derive password-reset or verification links from Host or
        // X-Forwarded-Host in production. Those values are controlled by the
        // incoming request unless every proxy in front of the app is configured
        // perfectly, which makes them unsuitable for security-sensitive links.
        if (!configuredBaseUrl.isBlank()) return configuredBaseUrl;

        if (!development) return null;

        // A tunnel URL is safe here because it comes from local configuration,
        // not from request headers. This keeps ngrok-based development usable.
        if (!configuredTunnelUrl.isBlank()) return configuredTunnelUrl;

        // Cloudflare Quick Tunnel URLs are read only from a local runtime source
        // populated by cloudflared, never from Host/X-Forwarded-Host headers.
        String cloudflareUrl = cloudflarePublicUrlProvider.current().orElse("");
        if (!cloudflareUrl.isBlank()) return cloudflareUrl;

        String host = request.getServerName() == null
                ? ""
                : request.getServerName().toLowerCase(Locale.ROOT);
        return isLocalHost(host) ? localOrigin(request) : null;
    }

    private String localOrigin(HttpServletRequest request) {
        int port = request.getServerPort();
        return port > 0 && port != 80 ? "http://localhost:" + port : "http://localhost";
    }

    private boolean isLocalHost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)
                || "::1".equals(host);
    }

    private String normalizeConfigured(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim().replaceAll("/+$", ""));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
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
