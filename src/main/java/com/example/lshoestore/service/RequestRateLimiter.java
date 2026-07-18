package com.example.lshoestore.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RequestRateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String scope, HttpServletRequest request, Authentication authentication,
                         int limit, Duration duration) {
        String identity = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())
                ? "user:" + authentication.getName().toLowerCase()
                : "ip:" + request.getRemoteAddr();
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

    private record Window(long startedAt, int count) {}
}
