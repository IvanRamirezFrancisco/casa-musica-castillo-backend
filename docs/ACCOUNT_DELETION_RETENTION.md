# Eliminación de cuenta · matriz candidata

La recepción y la ejecución automática se habilitan por separado. Ambas están deshabilitadas por defecto. El identificador aleatorio del acuse permite consultar únicamente el estado y las fechas, incluso tras cerrar la cuenta. Debe conservarse en privado.

| Categoría | Tratamiento implementado o propuesto | Plazo y justificación |
| --- | --- | --- |
| Perfil, direcciones, favoritos, carrito sin asociaciones sujetas a revisión, reseñas y votos | Borrado transaccional para una cuenta elegible. | En la ejecución autorizada. |
| Sesiones, tokens, códigos y secretos de autenticación | Borrado con la cuenta. Un JWT anterior deja de tener sesión válida. | En la ejecución autorizada. |
| Pedidos, cupones usados, pagos, comprobantes y referencias contables | La solicitud pasa a revisión. Validar qué referencias y datos mínimos deben conservarse o anonimizarse. | Pendientes de fundamento y plazo aprobados por el responsable. |
| Auditoría y referencias administrativas | La solicitud pasa a revisión. Identificar obligaciones y eliminar los campos que no deban mantenerse. | Pendientes de fundamento y plazo aprobados por el responsable. |
| Acuse sin identidad, copias de respaldo y registros de proveedores | El acuse conserva estado y fechas. Quedan pendientes la expiración del acuse, el tratamiento en copias y la ejecución con proveedores. | Pendientes de confirmación operativa y jurídica. |

El flujo automático solo cubre cuentas elegibles. Las solicitudes en revisión requieren un procedimiento autorizado que defina el resultado, sus plazos y cómo se confirma al usuario. Antes de activar en producción, validar los datos jurídicos de los documentos candidatos, los plazos de todas las excepciones y el inventario de copias y proveedores.
