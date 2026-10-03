package com.example.lshoestore;

import java.net.URI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LshoeStoreApplication {

    public static void main(String[] args) {
        configureManagedPostgres();
        configureNorthflankPublicUrl();
        SpringApplication.run(LshoeStoreApplication.class, args);
    }

    /**
     * Local development can keep using a JDBC URL plus DB_USERNAME/DB_PASSWORD.
     * Managed PostgreSQL providers, including Northflank, commonly expose a single
     * postgresql://user:password@host/database URL. Convert that URL to Spring JDBC
     * properties before Spring creates the DataSource.
     */
    private static void configureManagedPostgres() {
        String raw = System.getenv("DATABASE_URL");
        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return;
        }
        if (!raw.startsWith("postgresql://") && !raw.startsWith("postgres://")) {
            return;
        }

        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("DATABASE_URL is missing a host name");
            }
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }

            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                // libpq/Neon commonly uses channel_binding while pgJDBC uses channelBinding.
                query = query.replace("channel_binding=", "channelBinding=");
            }

            String jdbcUrl = "jdbc:postgresql://" + host + port + path
                    + (query == null || query.isBlank() ? "" : "?" + query);
            System.setProperty("spring.datasource.url", jdbcUrl);

            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                int separator = userInfo.indexOf(':');
                String username = separator >= 0 ? userInfo.substring(0, separator) : userInfo;
                String password = separator >= 0 ? userInfo.substring(separator + 1) : "";
                if (!username.isBlank()) {
                    System.setProperty("spring.datasource.username", username);
                }
                System.setProperty("spring.datasource.password", password);
            }

            System.out.println("[startup] Managed PostgreSQL URL configured for Spring Boot.");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid DATABASE_URL for PostgreSQL deployment", ex);
        }
    }

    /**
     * Northflank injects NF_HOSTS/NF_HOSTS_CUSTOM for public ports. Use that
     * platform-controlled hostname for verification/reset links when the operator
     * has not explicitly provided APP_PUBLIC_BASE_URL.
     */
    private static void configureNorthflankPublicUrl() {
        String configured = System.getenv("APP_PUBLIC_BASE_URL");
        if (configured != null && !configured.isBlank()) {
            return;
        }

        String hosts = firstNonBlank(
                System.getenv("NF_HOSTS_CUSTOM"),
                System.getenv("NF_HOSTS")
        );
        if (hosts == null) {
            return;
        }

        for (String candidate : hosts.split(",")) {
            String value = candidate.trim();
            if (value.isBlank()) {
                continue;
            }
            String url = value.startsWith("http://") || value.startsWith("https://")
                    ? value
                    : "https://" + value;
            try {
                URI uri = URI.create(url);
                if (uri.getHost() != null && uri.getUserInfo() == null
                        && uri.getQuery() == null && uri.getFragment() == null) {
                    System.setProperty("app.public-base-url", url.replaceAll("/+$", ""));
                    System.out.println("[startup] Northflank public URL configured from NF_HOSTS.");
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next Northflank hostname if one entry is malformed.
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
