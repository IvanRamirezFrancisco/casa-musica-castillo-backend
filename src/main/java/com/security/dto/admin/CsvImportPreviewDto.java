package com.security.dto.admin;

import java.util.List;

/**
 * Resultado completo de la fase de previsualización de importación CSV.
 *
 * <p>
 * Se devuelve antes de confirmar la importación para que el usuario pueda
 * revisar los datos, deseleccionar filas con errores y elegir la regla de
 * colisión.
 * </p>
 *
 * @param headers    Cabeceras encontradas en el CSV
 * @param rows       Todas las filas parseadas con validación individual
 * @param totalRows  Número total de filas de datos (sin cabecera)
 * @param validCount Filas sin errores
 * @param errorCount Filas con al menos un error
 * @param fileName   Nombre original del archivo subido
 * @param fileSizeKb Tamaño del archivo en KB
 */
public record CsvImportPreviewDto(
        List<String> headers,
        List<CsvPreviewRowDto> rows,
        int totalRows,
        int validCount,
        int errorCount,
        String fileName,
        double fileSizeKb) {
}
