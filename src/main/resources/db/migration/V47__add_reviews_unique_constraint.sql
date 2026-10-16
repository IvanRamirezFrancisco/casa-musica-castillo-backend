-- ============================================================
-- V47: Agregar constraint UNIQUE para las reseñas
-- Impide que un usuario deje más de una reseña por producto
-- ============================================================

ALTER TABLE catalog.product_reviews
ADD CONSTRAINT uq_product_reviews_user_product
UNIQUE (user_id, product_id);
