package com.example.lshoestore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;

@Component
@Order(-1000)
public class DataMigrationRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);
    private static final long MIGRATION_LOCK_ID = 4_837_219_071L;
    private static final String CART_SIZE_UNIQUE_INDEX = "uk_saved_cart_user_product_size";
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
    private final TransactionTemplate transactionTemplate;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        transactionTemplate.executeWithoutResult(status -> {
            // Prevent two application instances from changing the same schema concurrently.
            runRequiredStatement("migration advisory lock",
                    "SELECT pg_advisory_xact_lock(" + MIGRATION_LOCK_ID + ")");

            migrateSelectedSizes();
            migrateOrderItemSnapshots();
            runRequiredUpdate("user session versions",
                    "UPDATE users SET session_version = 0 WHERE session_version IS NULL");
            runRequiredUpdate("product versions",
                    "UPDATE product SET version = 0 WHERE version IS NULL");
            runRequiredUpdate("saved cart versions",
                    "UPDATE saved_cart_items SET version = 0 WHERE version IS NULL");
            runRequiredUpdate("product creation timestamps",
                    "UPDATE product SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
            runRequiredUpdate("product update timestamps",
                    "UPDATE product SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP) WHERE updated_at IS NULL");
            runRequiredUpdate("historical completed orders",
                    "UPDATE orders SET completed_at = created_at WHERE status = 'HOAN_THANH' AND completed_at IS NULL");
            runRequiredUpdate("negative product stocks", "UPDATE product SET stock = 0 WHERE stock < 0");
            runRequiredUpdate("invalid saved cart quantities",
                    "UPDATE saved_cart_items SET quantity = 1 WHERE quantity <= 0");
            runRequiredUpdate("invalid original prices",
                    "UPDATE product SET old_price = NULL "
                            + "WHERE old_price IS NOT NULL AND (old_price <= 0 OR old_price < price)");

            addDataConstraints();
        });

        // Cosmetic migration is intentionally outside the required transaction: a bad demo
        // image must not roll back or block the schema/data-integrity migrations above.
        migrateLegacyDemoImages();
    }

    private void migrateOrderItemSnapshots() {
        runRequiredStatement("order item product-name snapshot column",
                "ALTER TABLE order_item ADD COLUMN IF NOT EXISTS product_name VARCHAR(160)");
        runRequiredStatement("order item product-brand snapshot column",
                "ALTER TABLE order_item ADD COLUMN IF NOT EXISTS product_brand VARCHAR(100)");

        runRequiredUpdate("historical order product names",
                "UPDATE order_item oi SET product_name = "
                        + "COALESCE(NULLIF(BTRIM(oi.product_name), ''), "
                        + "NULLIF(BTRIM(p.name), ''), 'Sản phẩm') "
                        + "FROM product p WHERE oi.product_id = p.id "
                        + "AND (oi.product_name IS NULL OR BTRIM(oi.product_name) = '')");
        runRequiredUpdate("historical order product brands",
                "UPDATE order_item oi SET product_brand = "
                        + "COALESCE(NULLIF(BTRIM(oi.product_brand), ''), "
                        + "NULLIF(BTRIM(p.brand), ''), 'Không rõ') "
                        + "FROM product p WHERE oi.product_id = p.id "
                        + "AND (oi.product_brand IS NULL OR BTRIM(oi.product_brand) = '')");
        runRequiredUpdate("trimmed historical order product names",
                "UPDATE order_item SET product_name = BTRIM(product_name) "
                        + "WHERE product_name IS NOT NULL AND product_name <> BTRIM(product_name)");
        runRequiredUpdate("trimmed historical order product brands",
                "UPDATE order_item SET product_brand = BTRIM(product_brand) "
                        + "WHERE product_brand IS NOT NULL AND product_brand <> BTRIM(product_brand)");

        runRequiredStatement("order item product name not-null",
                "ALTER TABLE order_item ALTER COLUMN product_name SET NOT NULL");
        runRequiredStatement("order item product brand not-null",
                "ALTER TABLE order_item ALTER COLUMN product_brand SET NOT NULL");
    }

    private void migrateSelectedSizes() {
        runRequiredStatement("saved cart selected-size column",
                "ALTER TABLE saved_cart_items ADD COLUMN IF NOT EXISTS selected_size VARCHAR(160)");
        runRequiredStatement("order item selected-size column",
                "ALTER TABLE order_item ADD COLUMN IF NOT EXISTS selected_size VARCHAR(160)");

        String normalizedSeparators = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE("
                + "COALESCE(p.size_text, ''), ';', ','), '/', ','), '|', ','), CHR(10), ','), CHR(13), ',')";
        String firstSizeExpression = "COALESCE((SELECT BTRIM(size_value.value) "
                + "FROM REGEXP_SPLIT_TO_TABLE(" + normalizedSeparators + ", ',') AS size_value(value) "
                + "WHERE BTRIM(size_value.value) <> '' LIMIT 1), 'Không áp dụng')";

        runRequiredUpdate("saved cart selected sizes",
                "UPDATE saved_cart_items s SET selected_size = " + firstSizeExpression
                        + " FROM product p WHERE s.product_id = p.id "
                        + "AND (s.selected_size IS NULL OR BTRIM(s.selected_size) = '')");
        runRequiredUpdate("historical order selected sizes",
                "UPDATE order_item oi SET selected_size = " + firstSizeExpression
                        + " FROM product p WHERE oi.product_id = p.id "
                        + "AND (oi.selected_size IS NULL OR BTRIM(oi.selected_size) = '')");
        runRequiredUpdate("trimmed saved cart selected sizes",
                "UPDATE saved_cart_items SET selected_size = BTRIM(selected_size) "
                        + "WHERE selected_size IS NOT NULL AND selected_size <> BTRIM(selected_size)");
        runRequiredUpdate("trimmed historical order selected sizes",
                "UPDATE order_item SET selected_size = BTRIM(selected_size) "
                        + "WHERE selected_size IS NOT NULL AND selected_size <> BTRIM(selected_size)");

        dropLegacySavedCartUniqueness();
        mergeDuplicateSavedCartRows();

        runRequiredStatement("saved cart selected size not-null",
                "ALTER TABLE saved_cart_items ALTER COLUMN selected_size SET NOT NULL");
        runRequiredStatement("order item selected size not-null",
                "ALTER TABLE order_item ALTER COLUMN selected_size SET NOT NULL");
        ensureSizeAwareSavedCartUniqueIndex();
    }

    private void dropLegacySavedCartUniqueness() {
        List<String> constraints;
        try {
            constraints = jdbcTemplate.queryForList(
                    "SELECT con.conname FROM pg_constraint con "
                            + "JOIN pg_class rel ON rel.oid = con.conrelid "
                            + "JOIN pg_namespace ns ON ns.oid = rel.relnamespace "
                            + "WHERE ns.nspname = current_schema() "
                            + "AND rel.relname = 'saved_cart_items' AND con.contype = 'u' "
                            + "AND ("
                            + "  REPLACE(REPLACE(pg_get_constraintdef(con.oid), ' ', ''), CHR(34), '') "
                            + "      ILIKE '%(user_id,product_id)%' "
                            + "  OR REPLACE(REPLACE(pg_get_constraintdef(con.oid), ' ', ''), CHR(34), '') "
                            + "      ILIKE '%(product_id,user_id)%'"
                            + ") AND pg_get_constraintdef(con.oid) NOT ILIKE '%selected_size%'",
                    String.class);
        } catch (Exception exception) {
            throw migrationFailure("legacy saved-cart constraints", exception);
        }
        for (String constraint : constraints) {
            runRequiredStatement("legacy saved-cart constraint " + constraint,
                    "ALTER TABLE saved_cart_items DROP CONSTRAINT IF EXISTS " + quoteIdentifier(constraint));
            log.info("Dropped legacy saved-cart unique constraint {}", constraint);
        }

        List<String> indexes;
        try {
            indexes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes "
                            + "WHERE schemaname = current_schema() AND tablename = 'saved_cart_items' "
                            + "AND indexdef ILIKE 'CREATE UNIQUE INDEX%' "
                            + "AND ("
                            + "  REPLACE(REPLACE(indexdef, ' ', ''), CHR(34), '') "
                            + "      ILIKE '%(user_id,product_id)%' "
                            + "  OR REPLACE(REPLACE(indexdef, ' ', ''), CHR(34), '') "
                            + "      ILIKE '%(product_id,user_id)%'"
                            + ") AND indexdef NOT ILIKE '%selected_size%'",
                    String.class);
        } catch (Exception exception) {
            throw migrationFailure("legacy saved-cart indexes", exception);
        }
        for (String index : indexes) {
            runRequiredStatement("legacy saved-cart index " + index,
                    "DROP INDEX IF EXISTS " + quoteIdentifier(index));
            log.info("Dropped legacy saved-cart unique index {}", index);
        }
    }

    private void mergeDuplicateSavedCartRows() {
        runRequiredUpdate("merged duplicate saved-cart quantities",
                "UPDATE saved_cart_items keeper "
                        + "SET quantity = LEAST(merged.total_quantity, 2147483647)::INTEGER "
                        + "FROM ("
                        + "  SELECT MIN(id) AS keep_id, "
                        + "         SUM(GREATEST(quantity, 1)::BIGINT) AS total_quantity "
                        + "  FROM saved_cart_items "
                        + "  GROUP BY user_id, product_id, LOWER(BTRIM(selected_size)) "
                        + "  HAVING COUNT(*) > 1"
                        + ") merged WHERE keeper.id = merged.keep_id");

        runRequiredUpdate("removed duplicate saved-cart rows",
                "DELETE FROM saved_cart_items duplicate USING saved_cart_items keeper "
                        + "WHERE duplicate.user_id = keeper.user_id "
                        + "AND duplicate.product_id = keeper.product_id "
                        + "AND LOWER(BTRIM(duplicate.selected_size)) = LOWER(BTRIM(keeper.selected_size)) "
                        + "AND duplicate.id > keeper.id");
    }

    private void ensureSizeAwareSavedCartUniqueIndex() {
        List<String> definitions;
        try {
            definitions = jdbcTemplate.queryForList(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                            + "AND tablename = 'saved_cart_items' AND indexname = ?",
                    String.class,
                    CART_SIZE_UNIQUE_INDEX);
        } catch (Exception exception) {
            throw migrationFailure("saved-cart unique index inspection", exception);
        }

        boolean alreadyCorrect = definitions.stream()
                .map(definition -> definition.toLowerCase(Locale.ROOT).replace(" ", ""))
                .anyMatch(definition -> definition.contains("lower(btrim")
                        && definition.contains("user_id")
                        && definition.contains("product_id")
                        && definition.contains("selected_size"));
        if (alreadyCorrect) return;

        if (!definitions.isEmpty()) {
            runRequiredStatement("outdated saved-cart unique index",
                    "DROP INDEX IF EXISTS " + quoteIdentifier(CART_SIZE_UNIQUE_INDEX));
        }
        runRequiredStatement("size-aware saved-cart uniqueness",
                "CREATE UNIQUE INDEX " + quoteIdentifier(CART_SIZE_UNIQUE_INDEX)
                        + " ON saved_cart_items(user_id, product_id, LOWER(BTRIM(selected_size)))");
    }

    private void addDataConstraints() {
        addConstraintIfMissing("product", "chk_product_stock_nonnegative", "CHECK (stock >= 0)");
        addConstraintIfMissing("product", "chk_product_price_positive", "CHECK (price > 0)");
        addConstraintIfMissing("product", "chk_product_old_price_valid",
                "CHECK (old_price IS NULL OR old_price >= price)");
        addConstraintIfMissing("saved_cart_items", "chk_saved_cart_quantity_positive",
                "CHECK (quantity > 0)");
        addConstraintIfMissing("saved_cart_items", "chk_saved_cart_selected_size_nonblank",
                "CHECK (BTRIM(selected_size) <> '')");
        addConstraintIfMissing("order_item", "chk_order_item_quantity_positive",
                "CHECK (quantity > 0)");
        addConstraintIfMissing("order_item", "chk_order_item_selected_size_nonblank",
                "CHECK (BTRIM(selected_size) <> '')");
        addConstraintIfMissing("order_item", "chk_order_item_price_nonnegative",
                "CHECK (price >= 0)");
        addConstraintIfMissing("order_item", "chk_order_item_product_name_nonblank",
                "CHECK (BTRIM(product_name) <> '')");
        addConstraintIfMissing("order_item", "chk_order_item_product_brand_nonblank",
                "CHECK (BTRIM(product_brand) <> '')");
        addConstraintIfMissing("orders", "chk_order_total_nonnegative",
                "CHECK (total >= 0)");
    }

    private void addConstraintIfMissing(String table, String name, String definition) {
        Long count;
        try {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint con "
                            + "JOIN pg_class rel ON rel.oid = con.conrelid "
                            + "JOIN pg_namespace ns ON ns.oid = rel.relnamespace "
                            + "WHERE ns.nspname = current_schema() AND rel.relname = ? AND con.conname = ?",
                    Long.class,
                    table,
                    name);
        } catch (Exception exception) {
            throw migrationFailure("constraint inspection for " + name, exception);
        }
        if (count != null && count > 0) return;

        runRequiredStatement("database constraint " + name,
                "ALTER TABLE " + table + " ADD CONSTRAINT " + quoteIdentifier(name) + " " + definition);
        log.info("Added database constraint {}", name);
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
            // Image rotation is cosmetic and must not prevent the store from starting.
            log.warn("Legacy demo image migration was skipped: {}", exception.getMessage());
        }
    }

    private int runRequiredUpdate(String description, String sql) {
        try {
            int updated = jdbcTemplate.update(sql);
            if (updated > 0) log.info("Migrated {} row(s) for {}", updated, description);
            return updated;
        } catch (Exception exception) {
            throw migrationFailure(description, exception);
        }
    }

    private void runRequiredStatement(String description, String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception exception) {
            throw migrationFailure(description, exception);
        }
    }

    private IllegalStateException migrationFailure(String description, Exception exception) {
        log.error("Required migration failed for {}: {}", description, exception.getMessage());
        return new IllegalStateException("Required database migration failed: " + description, exception);
    }

    private String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
