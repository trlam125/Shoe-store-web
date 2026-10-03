-- Container deployments can use disposable filesystems. Store administrator-uploaded
-- product images in PostgreSQL when PRODUCT_IMAGE_STORAGE=database so uploads survive
-- cold starts, scaling, and redeployments. Local development may keep filesystem mode.
CREATE TABLE IF NOT EXISTS product_image_asset (
    filename VARCHAR(80) PRIMARY KEY,
    content_type VARCHAR(50) NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_product_image_filename
        CHECK (filename ~ '^[0-9a-fA-F-]{36}\.(jpg|png|webp)$'),
    CONSTRAINT chk_product_image_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp'))
);

CREATE INDEX IF NOT EXISTS idx_product_image_asset_created_at
    ON product_image_asset(created_at);
