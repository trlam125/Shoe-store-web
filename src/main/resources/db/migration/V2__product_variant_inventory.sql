-- Manage inventory per product size while keeping product.stock and product.size_text
-- as denormalized summaries for the existing storefront and AI reports.

CREATE TABLE IF NOT EXISTS product_variant (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    product_id BIGINT NOT NULL,
    size VARCHAR(160) NOT NULL,
    stock INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- Convert each legacy product into size variants. Because the old schema only stored
-- a total stock number, distribute that total as evenly as possible across its sizes.
WITH raw_sizes AS (
    SELECT p.id AS product_id,
           GREATEST(COALESCE(p.stock, 0), 0) AS total_stock,
           BTRIM(token.value) AS size,
           token.ordinality AS source_order
    FROM product p
    CROSS JOIN LATERAL REGEXP_SPLIT_TO_TABLE(
        COALESCE(p.size_text, ''), E'[,;/|\\n\\r]+'
    ) WITH ORDINALITY AS token(value, ordinality)
    WHERE BTRIM(token.value) <> ''
      AND NOT EXISTS (
          SELECT 1 FROM product_variant existing WHERE existing.product_id = p.id
      )
), deduplicated_sizes AS (
    SELECT DISTINCT ON (product_id, LOWER(size))
           product_id, total_stock, size, source_order
    FROM raw_sizes
    ORDER BY product_id, LOWER(size), source_order
), numbered_sizes AS (
    SELECT product_id, total_stock, size, source_order,
           ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY source_order, LOWER(size)) AS size_number,
           COUNT(*) OVER (PARTITION BY product_id) AS size_count
    FROM deduplicated_sizes
)
INSERT INTO product_variant(product_id, size, stock, enabled)
SELECT product_id,
       size,
       (total_stock / size_count
           + CASE WHEN size_number <= (total_stock % size_count) THEN 1 ELSE 0 END)::INTEGER,
       TRUE
FROM numbered_sizes;

INSERT INTO product_variant(product_id, size, stock, enabled)
SELECT p.id, 'Không áp dụng', GREATEST(COALESCE(p.stock, 0), 0), TRUE
FROM product p
WHERE NOT EXISTS (
    SELECT 1 FROM product_variant variant WHERE variant.product_id = p.id
);

-- Canonicalize duplicated rows before adding the case-insensitive uniqueness rule.
UPDATE product_variant keeper
SET stock = LEAST(merged.total_stock, 2147483647)::INTEGER,
    enabled = merged.any_enabled
FROM (
    SELECT MIN(id) AS keep_id,
           SUM(GREATEST(stock, 0)::BIGINT) AS total_stock,
           BOOL_OR(enabled) AS any_enabled
    FROM product_variant
    GROUP BY product_id, LOWER(BTRIM(size))
    HAVING COUNT(*) > 1
) merged
WHERE keeper.id = merged.keep_id;

DELETE FROM product_variant duplicate
USING product_variant keeper
WHERE duplicate.product_id = keeper.product_id
  AND LOWER(BTRIM(duplicate.size)) = LOWER(BTRIM(keeper.size))
  AND duplicate.id > keeper.id;

UPDATE product_variant SET size = BTRIM(size)
WHERE size <> BTRIM(size);
UPDATE product_variant SET stock = 0 WHERE stock < 0;
UPDATE product_variant SET enabled = TRUE WHERE enabled IS NULL;
UPDATE product_variant SET version = 0 WHERE version IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_product_variant_product_size
    ON product_variant(product_id, LOWER(BTRIM(size)));
CREATE INDEX IF NOT EXISTS idx_product_variant_product
    ON product_variant(product_id);
CREATE INDEX IF NOT EXISTS idx_product_variant_enabled_stock
    ON product_variant(enabled, stock);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_variant_product') THEN
        ALTER TABLE product_variant ADD CONSTRAINT fk_product_variant_product
            FOREIGN KEY (product_id) REFERENCES product(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_variant_size_nonblank') THEN
        ALTER TABLE product_variant ADD CONSTRAINT chk_product_variant_size_nonblank
            CHECK (BTRIM(size) <> '');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_variant_stock_nonnegative') THEN
        ALTER TABLE product_variant ADD CONSTRAINT chk_product_variant_stock_nonnegative
            CHECK (stock >= 0);
    END IF;
END $$;

-- Refresh legacy summaries from enabled variants.
UPDATE product p
SET stock = summary.total_stock,
    size_text = summary.size_text
FROM (
    SELECT product_id,
           LEAST(SUM(stock::BIGINT), 2147483647)::INTEGER AS total_stock,
           STRING_AGG(size, ', ' ORDER BY LOWER(size)) AS size_text
    FROM product_variant
    WHERE enabled = TRUE
    GROUP BY product_id
) summary
WHERE p.id = summary.product_id;

UPDATE product p
SET stock = 0,
    size_text = NULL
WHERE NOT EXISTS (
    SELECT 1 FROM product_variant variant
    WHERE variant.product_id = p.id AND variant.enabled = TRUE
);
