-- Migración V37: Añadir columnas de almacenamiento en la nube a categorías
-- Para soportar la subida de imágenes desde dispositivo al estilo productos/marcas

ALTER TABLE catalog.categories 
ADD COLUMN IF NOT EXISTS image_public_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS image_provider VARCHAR(50);
