-- V43__add_shipping_contact_info.sql
-- Añadir campos de contacto para cotizaciones de envío a sales.shipping_settings

ALTER TABLE sales.shipping_settings
ADD COLUMN contact_whatsapp VARCHAR(20),
ADD COLUMN contact_phone VARCHAR(20),
ADD COLUMN quote_message TEXT,
ADD COLUMN business_hours VARCHAR(100);

-- Datos por defecto (opcional, pero útil para tener un baseline si la tabla ya tiene el registro id=1)
UPDATE sales.shipping_settings
SET 
    contact_whatsapp = '527711234567',
    contact_phone = '7711234567',
    quote_message = 'Hola, quiero cotizar el envío de mi pedido.',
    business_hours = 'Lunes a Sábado: 10:00 AM - 8:00 PM'
WHERE id = 1;
