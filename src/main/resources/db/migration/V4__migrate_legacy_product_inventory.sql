-- Convert products created before size-based inventory was introduced.
-- Only products without any product_variant rows are migrated.
-- The legacy product stock is distributed as evenly as possible across its
-- configured sizes so the parent total stock remains unchanged.
WITH legacy_products AS (
    SELECT p.id,
           GREATEST(p.stock, 0) AS total_stock,
           p.created_at,
           COALESCE(NULLIF(BTRIM(p.size_text), ''), 'Không áp dụng') AS legacy_sizes
    FROM product p
    WHERE NOT EXISTS (
        SELECT 1
        FROM product_variant pv
        WHERE pv.product_id = p.id
    )
),
raw_sizes AS (
    SELECT lp.id AS product_id,
           lp.total_stock,
           lp.created_at,
           BTRIM(parts.size) AS size
    FROM legacy_products lp
    CROSS JOIN LATERAL regexp_split_to_table(
        lp.legacy_sizes,
        '[,;/|' || CHR(10) || CHR(13) || ']+'
    ) AS parts(size)
),
deduplicated_sizes AS (
    SELECT DISTINCT ON (product_id, LOWER(size))
           product_id,
           total_stock,
           created_at,
           size
    FROM raw_sizes
    WHERE size <> ''
    ORDER BY product_id, LOWER(size), size
),
numbered_sizes AS (
    SELECT product_id,
           total_stock,
           created_at,
           size,
           ROW_NUMBER() OVER (
               PARTITION BY product_id
               ORDER BY LOWER(size), size
           ) AS size_number,
           COUNT(*) OVER (PARTITION BY product_id) AS size_count
    FROM deduplicated_sizes
)
INSERT INTO product_variant(version, product_id, size, stock, enabled, created_at)
SELECT 0,
       product_id,
       size,
       (total_stock / size_count)
           + CASE WHEN size_number <= (total_stock % size_count) THEN 1 ELSE 0 END,
       TRUE,
       COALESCE(created_at, CURRENT_TIMESTAMP)
FROM numbered_sizes;

-- Keep the legacy summary columns synchronized for reports and the AI service.
WITH variant_summary AS (
    SELECT p.id AS product_id,
           COALESCE(SUM(v.stock) FILTER (WHERE v.enabled), 0)::INTEGER AS total_stock,
           STRING_AGG(v.size, ', ' ORDER BY LOWER(v.size), v.id)
               FILTER (WHERE v.enabled) AS size_text
    FROM product p
    JOIN product_variant v ON v.product_id = p.id
    GROUP BY p.id
)
UPDATE product p
SET stock = summary.total_stock,
    size_text = summary.size_text,
    version = p.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM variant_summary summary
WHERE p.id = summary.product_id
  AND (
      p.stock IS DISTINCT FROM summary.total_stock
      OR p.size_text IS DISTINCT FROM summary.size_text
  );
