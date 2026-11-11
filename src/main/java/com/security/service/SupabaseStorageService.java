package com.security.service;

import com.security.config.SupabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Servicio de acceso a Supabase Storage.
 *
 * <p>Expone dos operaciones:
 * <ol>
 *   <li>{@link #uploadBackup(Path, String)} — sube un archivo al bucket privado.</li>
 *   <li>{@link #generateSignedUrl(String, int)} — crea una URL firmada de descarga.</li>
 * </ol>
 *
 * <p>El {@link RestClient} se construye una sola vez (configuración inmutable)
 * con la base URL del proyecto Supabase y el header de autorización.
 */
@Service
public class SupabaseStorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

    private final SupabaseProperties props;
    private final RestClient storageClient;

    public SupabaseStorageService(SupabaseProperties props, RestClient.Builder builder) {
        this.props = props;
        var transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(Duration.ofMinutes(5));
        this.storageClient = builder
                .baseUrl(props.getUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getServiceKey())
                .build();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Bucket management
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Crea el bucket privado en Supabase Storage si todavía no existe.
     *
     * <p>Llama a:
     * <pre>POST {supabaseUrl}/storage/v1/bucket
     * Body: {"id": "{bucket}", "name": "{bucket}", "public": false}</pre>
     *
     * <ul>
     *   <li>Si la respuesta es {@code 200} / {@code 201} — bucket creado correctamente.</li>
     *   <li>Si la respuesta es {@code 400} con código {@code "Duplicate"} — el bucket
     *       ya existe; se ignora silenciosamente.</li>
     *   <li>Cualquier otro error HTTP se propaga como {@link RuntimeException}.</li>
     * </ul>
     */
    public void ensureBucketExists() {
        String bucket = props.getBucketName();
        log.info("[Supabase] Verificando existencia del bucket '{}'…", bucket);

        try {
            storageClient.post()
                    .uri("/storage/v1/bucket")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "id",     bucket,
                            "name",   bucket,
                            "public", false
                    ))
                    .retrieve()
                    .toBodilessEntity();

            log.info("[Supabase] Bucket '{}' creado correctamente.", bucket);

        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            // 400 con "Duplicate" o "already exists" → el bucket ya existía → OK
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST
                    && (body.contains("Duplicate") || body.contains("already exists"))) {
                log.debug("[Supabase] Bucket '{}' ya existe — continuando.", bucket);
            } else {
                log.error("[Supabase] No se pudo crear el bucket '{}' — HTTP {}: {}",
                        bucket, ex.getStatusCode(), body);
                throw new RuntimeException(
                        "No se pudo crear el bucket de Supabase Storage '" + bucket + "': " + ex.getMessage(), ex);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Upload
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Sube {@code localFile} al bucket configurado con el nombre {@code storageFileName}.
     *
     * <p>Garantiza que el bucket existe (lo crea si es necesario) antes de subir.
     *
     * <p>Llama a:
     * <pre>POST {supabaseUrl}/storage/v1/object/{bucket}/{storageFileName}</pre>
     *
     * @param localFile       ruta al archivo temporal en disco
     * @param storageFileName nombre del objeto en el bucket
     *                        (p.ej. {@code backup_casamusica_20260306_030000.dump})
     * @return ruta del objeto en el bucket (igual que {@code storageFileName})
     * @throws RuntimeException si Supabase devuelve un error HTTP
     */
    public String uploadBackup(Path localFile, String storageFileName) {
        String uploadPath = "/storage/v1/object/" + props.getBucketName() + "/" + storageFileName;
        log.info("[Supabase] Subiendo {} → {}", localFile.getFileName(), uploadPath);

        try {
            storageClient.post()
                    .uri(uploadPath)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(localFile))
                    .retrieve()
                    .toBodilessEntity();

            log.info("[Supabase] Upload completado: {}", storageFileName);
            return storageFileName;

        } catch (RestClientResponseException ex) {
            log.error("[Supabase] Error al subir backup — HTTP {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new RuntimeException("Error al subir backup a Supabase Storage: " + ex.getMessage(), ex);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Signed URL
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Genera una URL firmada de tiempo limitado para descargar {@code objectPath}.
     *
     * <p>Llama a:
     * <pre>POST {supabaseUrl}/storage/v1/object/sign/{bucket}/{objectPath}
     * Body: {"expiresIn": expiresInSeconds}</pre>
     *
     * @param objectPath       ruta del objeto dentro del bucket
     * @param expiresInSeconds segundos de validez (p.ej. 3600 = 1 hora)
     * @return URL completa firmada lista para enviar al cliente
     * @throws RuntimeException si Supabase devuelve un error HTTP
     */
    public String generateSignedUrl(String objectPath, int expiresInSeconds) {
        String signPath = "/storage/v1/object/sign/" + props.getBucketName() + "/" + objectPath;
        log.debug("[Supabase] Generando URL firmada para '{}' ({} s)", objectPath, expiresInSeconds);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = storageClient.post()
                    .uri(signPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", expiresInSeconds))
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("signedURL")) {
                throw new RuntimeException("Respuesta inesperada de Supabase Storage al generar URL firmada");
            }

            String signedPath = (String) response.get("signedURL");
            // Supabase a veces devuelve /object/sign/... (sin el prefijo /storage/v1)
            // y otras veces devuelve /storage/v1/object/sign/... — normalizamos siempre.
            if (!signedPath.startsWith("/storage/v1")) {
                signedPath = "/storage/v1" + signedPath;
            }
            String fullUrl = props.getUrl() + signedPath;
            log.debug("[Supabase] URL firmada generada");
            return fullUrl;

        } catch (RestClientResponseException ex) {
            log.error("[Supabase] Error al generar URL firmada — HTTP {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new RuntimeException("Error al generar URL firmada: " + ex.getMessage(), ex);
        }
    }
}
