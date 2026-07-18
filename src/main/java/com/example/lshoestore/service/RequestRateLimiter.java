package com.example.lshoestore.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RequestRateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean trustProxyHeaders;

    public RequestRateLimiter(@Value("${app.trust-proxy-headers:true}") boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public boolean allow(String scope, HttpServletRequest request, Authentication authentication,
                         int limit, Duration duration) {
        String identity = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())
                ? "user:" + authentication.getName().toLowerCase()
                : "ip:" + resolveClientIp(request);
        String key = scope + ":" + identity;
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(duration.toMillis(), 1000);

        Window result = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt, existing.count + 1);
        });

        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > windowMillis * 2);
        }
        return result.count <= Math.max(limit, 1);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustProxyHeaders || !isTrustedProxy(remoteAddress)) return remoteAddress;

        String cloudflareIp = validIp(request.getHeader("CF-Connecting-IP"));
        if (cloudflareIp != null) return cloudflareIp;

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            for (String candidate : forwardedFor.split(",")) {
                String valid = validIp(candidate);
                if (valid != null) return valid;
            }
        }

        String realIp = validIp(request.getHeader("X-Real-IP"));
        return realIp == null ? remoteAddress : realIp;
    }

    private boolean isTrustedProxy(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String validIp(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 45 || !cleaned.matches("[0-9a-fA-F:.]+")) return null;
        try {
            InetAddress.getByName(cleaned);
            return cleaned;
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Window(long startedAt, int count) {}
}
