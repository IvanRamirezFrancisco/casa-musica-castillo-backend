-- V50__seed_verified_help_center_content.sql
-- Inserción inicial de contenido FAQ verificado
-- Se agregan categorías y preguntas cuyas respuestas coinciden estrictamente con la funcionalidad técnica.

-- 1. Insertar Categorías verificadas
INSERT INTO ops.help_categories (name, slug, description, icon_key, display_order, is_active, created_at, updated_at)
VALUES 
    ('Cuenta y seguridad', 'cuenta-seguridad', 'Gestión de tu cuenta, inicio de sesión y recuperación de contraseña.', 'person', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Compras y carrito', 'compras-carrito', 'Cómo agregar productos, cantidades y disponibilidad.', 'shopping_cart', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Pedidos', 'pedidos', 'Seguimiento, visualización y estados de tus pedidos.', 'receipt', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Pruebas de sonido', 'pruebas-sonido', 'Demostraciones e interacciones con los instrumentos.', 'headphones', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (slug) DO NOTHING;

-- 2. Insertar FAQs verificadas
WITH cats AS (
    SELECT id, slug FROM ops.help_categories
)
INSERT INTO ops.faqs (category_id, question, answer, display_order, is_active, is_featured, created_at, updated_at)
VALUES
    -- Cuenta y seguridad
    (
        (SELECT id FROM cats WHERE slug = 'cuenta-seguridad'),
        '¿Cómo creo una cuenta?',
        'Para crear una cuenta, haz clic en el icono de usuario en la parte superior derecha y selecciona "Crear cuenta". Deberás proporcionar tu nombre, apellidos, correo electrónico y una contraseña segura. Te enviaremos un correo para verificar tu cuenta antes de que puedas iniciar sesión.',
        10, true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM cats WHERE slug = 'cuenta-seguridad'),
        '¿Cómo recupero mi contraseña?',
        'Si olvidaste tu contraseña, ve a la página de inicio de sesión y selecciona "¿Olvidaste tu contraseña?". Ingresa tu correo electrónico y recibirás un enlace seguro para crear una nueva.',
        20, true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM cats WHERE slug = 'cuenta-seguridad'),
        '¿Cómo verifico mi correo electrónico?',
        'Al registrarte, enviamos automáticamente un enlace a tu correo. Revisa tu bandeja de entrada (y la carpeta de spam). Solo necesitas hacer clic en el enlace para verificar tu cuenta y activar tu acceso.',
        30, true, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    
    -- Compras y carrito
    (
        (SELECT id FROM cats WHERE slug = 'compras-carrito'),
        '¿Cómo agrego un producto al carrito?',
        'Navega por nuestro catálogo, haz clic en el instrumento o producto de tu interés y presiona el botón "Agregar al Carrito". Podrás ver el total actualizado en el panel lateral.',
        10, true, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM cats WHERE slug = 'compras-carrito'),
        '¿Cómo sé si un producto está disponible?',
        'En la página de cada producto encontrarás un indicador visual. Si el producto tiene stock, verás la etiqueta verde "En Stock" y el botón para agregarlo al carrito estará habilitado.',
        20, true, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    
    -- Pedidos
    (
        (SELECT id FROM cats WHERE slug = 'pedidos'),
        '¿Dónde consulto mis pedidos?',
        'Puedes ver el historial de tus compras iniciando sesión en tu cuenta y navegando a la sección "Mis Pedidos" desde tu perfil. Allí encontrarás la fecha, monto y estado actual de cada compra.',
        10, true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM cats WHERE slug = 'pedidos'),
        '¿Qué significan los estados del pedido?',
        'Los pedidos pasan por distintos estados: PENDING (en espera de pago o verificación), PROCESSING (en preparación en almacén), SHIPPED (enviado), DELIVERED (entregado al cliente) y CANCELLED (cancelado). Puedes ver estos estados en tiempo real en "Mis Pedidos".',
        20, true, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),

    -- Pruebas de sonido
    (
        (SELECT id FROM cats WHERE slug = 'pruebas-sonido'),
        '¿Dónde consulto las especificaciones y pruebas de sonido?',
        'Dentro de la página de detalle de cada producto (como guitarras o pianos), encontrarás pestañas dedicadas a "Especificaciones" y, si el instrumento cuenta con demostraciones grabadas, una sección de "Pruebas de sonido" para escucharlo antes de elegirlo.',
        10, true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
