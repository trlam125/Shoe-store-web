package com.example.lshoestore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(-1000)
public class DataMigrationRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);
    private static final String LEGACY_DEMO_IMAGE_MARKER = "photo-1542291026-7eec264c27ff";
    private static final List<String> DEMO_SHOE_IMAGES = List.of(
            "https://images.unsplash.com/photo-1542291026-7eec264c27ff?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1549298916-b41d501d3772?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1608231387042-66d1773070a5?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1600185365483-26d7a4cc7519?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1491553895911-0055eca6402d?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1525966222134-fcfa99b8ae77?q=80&w=900&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1605408499391-6368c628ef42?q=80&w=900&auto=format&fit=crop"
    );

    private final JdbcTemplate jdbcTemplate;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        runUpdate("user session versions",
                "UPDATE users SET session_version = 0 WHERE session_version IS NULL");
        runUpdate("product versions",
                "UPDATE product SET version = 0 WHERE version IS NULL");
        runUpdate("saved cart versions",
                "UPDATE saved_cart_items SET version = 0 WHERE version IS NULL");
        runUpdate("product creation timestamps",
                "UPDATE product SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        runUpdate("product update timestamps",
                "UPDATE product SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP) WHERE updated_at IS NULL");
        runUpdate("historical completed orders",
                "UPDATE orders SET completed_at = created_at "
                        + "WHERE status = 'HOAN_THANH' AND completed_at IS NULL");
        migrateLegacyDemoImages();
    }

    private void migrateLegacyDemoImages() {
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM product WHERE image_url LIKE ? ORDER BY id",
                    Long.class,
                    "%" + LEGACY_DEMO_IMAGE_MARKER + "%");
            if (ids.size() <= 1) return;

            int updated = 0;
            for (int index = 0; index < ids.size(); index++) {
                String imageUrl = DEMO_SHOE_IMAGES.get(index % DEMO_SHOE_IMAGES.size());
                updated += jdbcTemplate.update(
                        "UPDATE product SET image_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        imageUrl,
                        ids.get(index));
            }
            log.info("Migrated {} legacy demo product image(s) across {} distinct shoe photos",
                    updated, DEMO_SHOE_IMAGES.size());
        } catch (Exception exception) {
            log.debug("Legacy demo image migration skipped: {}", exception.getMessage());
        }
    }

    private void runUpdate(String description, String sql) {
        try {
            int updated = jdbcTemplate.update(sql);
            if (updated > 0) {
                log.info("Migrated {} row(s) for {}", updated, description);
            }
        } catch (Exception exception) {
            log.debug("Migration for {} skipped: {}", description, exception.getMessage());
        }
    }
}
