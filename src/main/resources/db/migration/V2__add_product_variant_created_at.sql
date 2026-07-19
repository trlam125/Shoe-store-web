-- Track when each size/variant became available so demand forecasting does not
-- backfill zero-sales days from the older parent product creation date.
ALTER TABLE product_variant
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

UPDATE product_variant pv
SET created_at = COALESCE(
        (SELECT p.created_at FROM product p WHERE p.id = pv.product_id),
        CURRENT_TIMESTAMP
    )
WHERE pv.created_at IS NULL;

ALTER TABLE product_variant
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE product_variant
    ALTER COLUMN created_at SET NOT NULL;
