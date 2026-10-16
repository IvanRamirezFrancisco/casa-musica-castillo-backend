-- V48__create_help_center_tables.sql
-- Creación de la arquitectura para el Centro de Ayuda bajo el esquema 'ops'
-- Esta migración no contiene permisos, solo estructura.

-- 1. Crear tabla de categorías
CREATE TABLE IF NOT EXISTS ops.help_categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(150) NOT NULL,
    description VARCHAR(500),
    icon_key VARCHAR(50),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_help_categories_slug UNIQUE (slug),
    CONSTRAINT chk_help_categories_order CHECK (display_order >= 0)
);

-- 2. Crear tabla de preguntas frecuentes (FAQs)
CREATE TABLE IF NOT EXISTS ops.faqs (
    id BIGSERIAL PRIMARY KEY,
    category_id BIGINT NOT NULL,
    question VARCHAR(300) NOT NULL,
    answer TEXT NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_featured BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_faq_category FOREIGN KEY (category_id) REFERENCES ops.help_categories(id), -- Sin ON DELETE CASCADE explícitamente
    CONSTRAINT chk_faqs_order CHECK (display_order >= 0)
);

-- 3. Crear índices útiles (Punto 19: índices para categoría y visualización pública)
CREATE INDEX IF NOT EXISTS idx_faqs_category_active ON ops.faqs(category_id, is_active);
CREATE INDEX IF NOT EXISTS idx_faqs_featured ON ops.faqs(is_featured, is_active);
CREATE INDEX IF NOT EXISTS idx_help_categories_active ON ops.help_categories(is_active);
