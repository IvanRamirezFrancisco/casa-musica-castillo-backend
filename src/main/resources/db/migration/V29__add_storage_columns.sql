-- Agrega columnas a catalog.product_images
ALTER TABLE catalog.product_images
ADD COLUMN public_id VARCHAR(255),
ADD COLUMN stored_filename VARCHAR(255),
ADD COLUMN content_type VARCHAR(100),
ADD COLUMN file_size_bytes BIGINT,
ADD COLUMN is_primary BOOLEAN DEFAULT false,
ADD COLUMN provider VARCHAR(50) DEFAULT 'LOCAL',
ADD COLUMN uploaded_by_id BIGINT;

-- Añadir constraint (si User está en auth)
ALTER TABLE catalog.product_images
ADD CONSTRAINT fk_product_images_uploaded_by
FOREIGN KEY (uploaded_by_id) REFERENCES auth.users(id) ON DELETE SET NULL;

-- Agrega columnas a catalog.brands
ALTER TABLE catalog.brands
ADD COLUMN logo_public_id VARCHAR(255),
ADD COLUMN logo_provider VARCHAR(50) DEFAULT 'LOCAL';
