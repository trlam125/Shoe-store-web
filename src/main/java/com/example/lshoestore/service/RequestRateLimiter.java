package com.example.lshoestore.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RequestRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);
    private static final String UPSERT_SQL = """
            INSERT INTO request_rate_limits
                (rate_key, window_started_at, window_seconds, request_count, expires_at)
            VALUES
                (?, CURRENT_TIMESTAMP, ?, 1, CURRENT_TIMESTAMP + make_interval(secs => ?))
            ON CONFLICT (rate_key) DO UPDATE SET
                window_started_at = CASE
                    WHEN request_rate_limits.expires_at <= CURRENT_TIMESTAMP
                      OR request_rate_limits.window_seconds <> EXCLUDED.window_seconds
                    THEN CURRENT_TIMESTAMP
                    ELSE request_rate_limits.window_started_at
                END,
                window_seconds = EXCLUDED.window_seconds,
                request_count = CASE
                    WHEN request_rate_limits.expires_at <= CURRENT_TIMESTAMP
                      OR request_rate_limits.window_seconds <> EXCLUDED.window_seconds
                    THEN 1
                    ELSE CASE
                        WHEN request_rate_limits.request_count >= 2147483647 THEN 2147483647
                        ELSE request_rate_limits.request_count + 1
                    END
                END,
                expires_at = CASE
                    WHEN request_rate_limits.expires_at <= CURRENT_TIMESTAMP
                      OR request_rate_limits.window_seconds <> EXCLUDED.window_seconds
                    THEN CURRENT_TIMESTAMP + make_interval(secs => EXCLUDED.window_seconds)
                    ELSE request_rate_limits.expires_at
                END
            RETURNING request_count
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean trustProxyHeaders;
    private final List<CidrBlock> trustedProxies;
    private final AtomicLong maintenanceCounter = new AtomicLong();
    private final Map<String, Window> fallbackWindows = new ConcurrentHashMap<>();

    public RequestRateLimiter(JdbcTemplate jdbcTemplate,
                              @Value("${app.trust-proxy-headers:false}") boolean trustProxyHeaders,
                              @Value("${app.trusted-proxy-cidrs:127.0.0.1/32,::1/128}")
                              String trustedProxyCidrs) {
        this.jdbcTemplate = jdbcTemplate;
        this.trustProxyHeaders = trustProxyHeaders;
        this.trustedProxies = parseCidrs(trustedProxyCidrs);
    }

    public boolean allow(String scope, HttpServletRequest request, Authentication authentication,
                         int limit, Duration duration) {
        String identity = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())
                ? "user:" + authentication.getName().toLowerCase(Locale.ROOT)
                : "ip:" + resolveClientIp(request);
        return allowKey(scope + ":" + identity, limit, duration);
    }

    public boolean allowIp(String scope, HttpServletRequest request, int limit, Duration duration) {
        return allowKey(scope + ":ip:" + resolveClientIp(request), limit, duration);
    }

    public boolean allowIdentity(String scope, String identity, int limit, Duration duration) {
        if (identity == null || identity.isBlank()) return true;
        String normalized = identity.trim().toLowerCase(Locale.ROOT);
        return allowKey(scope + ":identity:" + normalized, limit, duration);
    }

    private boolean allowKey(String rawKey, int limit, Duration duration) {
        int safeLimit = Math.max(limit, 1);
        long secondsLong = Math.max(duration.toSeconds(), 1L);
        int windowSeconds = (int) Math.min(secondsLong, Integer.MAX_VALUE);
        String storageKey = hash(rawKey);

        try {
            Integer count = jdbcTemplate.queryForObject(
                    UPSERT_SQL, Integer.class, storageKey, windowSeconds, windowSeconds);
            performMaintenanceOccasionally();
            return count != null && count <= safeLimit;
        } catch (DataAccessException exception) {
            // A temporary database error should not take the whole storefront
            // offline. The fallback is process-local and is used only until the
            // shared persistent limiter becomes available again.
            log.error("Persistent rate limiter failed; using local fallback", exception);
            return allowFallback(storageKey, safeLimit, Duration.ofSeconds(windowSeconds));
        }
    }

    private void performMaintenanceOccasionally() {
        if ((maintenanceCounter.incrementAndGet() & 255L) != 0L) return;
        try {
            jdbcTemplate.update("""
                    DELETE FROM request_rate_limits
                    WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
                    """);
        } catch (DataAccessException exception) {
            log.debug("Could not clean expired rate-limit rows", exception);
        }
    }

    private boolean allowFallback(String key, int limit, Duration duration) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(duration.toMillis(), 1000L);
        Window result = fallbackWindows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowMillis() != windowMillis
                    || now - existing.startedAt() >= windowMillis) {
                return new Window(now, 1, windowMillis);
            }
            return new Window(existing.startedAt(), existing.count() + 1, windowMillis);
        });

        if (fallbackWindows.size() > 10_000) {
            fallbackWindows.entrySet().removeIf(entry ->
                    now - entry.getValue().startedAt() > entry.getValue().windowMillis() * 2);
        }
        return result.count() <= limit;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddress = validIp(request.getRemoteAddr());
        if (remoteAddress == null) remoteAddress = "unknown";
        if (!trustProxyHeaders || !isTrustedProxy(remoteAddress)) return remoteAddress;

        List<String> forwardedChain = parseForwardedFor(request.getHeader("X-Forwarded-For"));
        if (!forwardedChain.isEmpty()) {
            String currentHop = remoteAddress;
            for (int index = forwardedChain.size() - 1; index >= 0; index--) {
                if (!isTrustedProxy(currentHop)) return currentHop;
                currentHop = forwardedChain.get(index);
            }
            return currentHop;
        }

        return remoteAddress;
    }

    private List<String> parseForwardedFor(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String candidate : header.split(",")) {
            String valid = validIp(candidate);
            if (valid != null) result.add(valid);
        }
        return result;
    }

    private boolean isTrustedProxy(String value) {
        String canonical = validIp(value);
        if (canonical == null) return false;
        for (CidrBlock block : trustedProxies) {
            if (block.contains(canonical)) return true;
        }
        return false;
    }

    private List<CidrBlock> parseCidrs(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        List<CidrBlock> blocks = new ArrayList<>();
        for (String value : configured.split(",")) {
            CidrBlock block = CidrBlock.parse(value.trim());
            if (block != null) blocks.add(block);
            else if (!value.isBlank()) log.warn("Ignoring invalid trusted proxy CIDR: {}", value.trim());
        }
        return List.copyOf(blocks);
    }

    private String validIp(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 45 || !cleaned.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(cleaned).getHostAddress();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Window(long startedAt, int count, long windowMillis) {}

    private record CidrBlock(byte[] network, int prefixLength) {
        static CidrBlock parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                String[] parts = value.split("/", -1);
                if (parts.length > 2 || parts[0].isBlank()
                        || !parts[0].matches("[0-9a-fA-F:.]+")) return null;
                InetAddress address = InetAddress.getByName(parts[0]);
                byte[] bytes = address.getAddress();
                int maxPrefix = bytes.length * 8;
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : maxPrefix;
                if (prefix < 0 || prefix > maxPrefix) return null;
                return new CidrBlock(mask(bytes, prefix), prefix);
            } catch (Exception ignored) {
                return null;
            }
        }

        boolean contains(String value) {
            try {
                byte[] address = InetAddress.getByName(value).getAddress();
                return address.length == network.length
                        && Arrays.equals(mask(address, prefixLength), network);
            } catch (Exception ignored) {
                return false;
            }
        }

        private static byte[] mask(byte[] source, int prefixLength) {
            byte[] result = Arrays.copyOf(source, source.length);
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            if (fullBytes < result.length && remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                result[fullBytes] = (byte) (result[fullBytes] & mask);
                fullBytes++;
            }
            Arrays.fill(result, fullBytes, result.length, (byte) 0);
            return result;
        }
    }
}
