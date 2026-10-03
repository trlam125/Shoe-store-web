package com.example.lshoestore;

import java.net.URI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LshoeStoreApplication {

    public static void main(String[] args) {
        configureManagedPostgres();
        SpringApplication.run(LshoeStoreApplication.class, args);
    }

    /**
     * Local development can keep using a JDBC URL plus DB_USERNAME/DB_PASSWORD.
     * Managed PostgreSQL providers such as Neon commonly expose a single
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


}
