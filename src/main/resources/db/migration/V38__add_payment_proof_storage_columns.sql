-- Agregar columnas de almacenamiento mejorado a la tabla de comprobantes de pago
ALTER TABLE sales.payment_proofs 
ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(50),
ADD COLUMN IF NOT EXISTS storage_key VARCHAR(1024),
ADD COLUMN IF NOT EXISTS file_url VARCHAR(2048),
ADD COLUMN IF NOT EXISTS resource_type VARCHAR(50),
ADD COLUMN IF NOT EXISTS storage_folder VARCHAR(500);

-- Comentarios descriptivos
COMMENT ON COLUMN sales.payment_proofs.storage_provider IS 'Proveedor de almacenamiento (e.g., CLOUDINARY, LOCAL)';
COMMENT ON COLUMN sales.payment_proofs.storage_key IS 'Clave única o public_id del archivo en el storage';
COMMENT ON COLUMN sales.payment_proofs.file_url IS 'URL directa para acceder o descargar el archivo si es remoto';
COMMENT ON COLUMN sales.payment_proofs.resource_type IS 'Tipo de recurso en Cloudinary (image, raw, auto)';
COMMENT ON COLUMN sales.payment_proofs.storage_folder IS 'Carpeta donde se guardó el archivo';
