package com.security.service;

import com.security.config.SupabaseProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseStorageServiceTest {
    @TempDir Path tempDir;

    @Test
    void uploadsAndSignsOnlyAfterVerifyingThatTheBucketIsPrivate() throws IOException {
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/bucket/synthetic", exchange -> {
            calls.add(exchange.getRequestMethod() + " bucket");
            respond(exchange, "{\"public\":false}");
        });
        server.createContext("/storage/v1/object/synthetic/synthetic.dump", exchange -> {
            calls.add(exchange.getRequestMethod() + " upload");
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{}");
        });
        server.createContext("/storage/v1/object/sign/synthetic/synthetic.dump", exchange -> {
            calls.add(exchange.getRequestMethod() + " sign");
            respond(exchange, "{\"signedURL\":\"/object/sign/synthetic/synthetic.dump?token=synthetic\"}");
        });
        server.start();
        try {
            SupabaseProperties properties = properties(server);
            var storage = new SupabaseStorageService(properties, RestClient.builder());
            Path archive = Files.writeString(tempDir.resolve("synthetic.dump"), "synthetic");
            assertEquals("synthetic.dump", storage.uploadBackup(archive, "synthetic.dump"));
            assertEquals(properties.getUrl() + "/storage/v1/object/sign/synthetic/synthetic.dump?token=synthetic",
                    storage.generateSignedUrl("synthetic.dump", 3600));
            assertEquals(List.of("GET bucket", "POST upload", "GET bucket", "POST sign"), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUploadAndDownloadForAPublicBucket() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/bucket/synthetic", exchange -> respond(exchange, "{\"public\":true}"));
        server.start();
        try {
            var storage = new SupabaseStorageService(properties(server), RestClient.builder());
            Path archive = Files.writeString(tempDir.resolve("synthetic.dump"), "synthetic");
            assertThrows(IllegalStateException.class, () -> storage.uploadBackup(archive, "synthetic.dump"));
            assertThrows(IllegalStateException.class, () -> storage.generateSignedUrl("synthetic.dump", 3600));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifiesPrivacyWhenSupabaseReportsAnExistingBucket() throws IOException {
        verifyExistingBucket(409, "{}", "{\"public\":false}", false);
        verifyExistingBucket(400, "{\"code\":\"Duplicate\"}", "{\"public\":false}", false);
        verifyExistingBucket(400, "{\"message\":\"already exists\"}", "{\"public\":true}", true);
        verifyExistingBucket(409, "{}", "", true);
    }

    @Test
    void rejectsAProviderErrorThatDoesNotRepresentAnExistingBucket() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/bucket", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        try {
            var storage = new SupabaseStorageService(properties(server), RestClient.builder());
            assertThrows(RuntimeException.class, storage::ensureBucketExists);
        } finally {
            server.stop(0);
        }
    }

    private void verifyExistingBucket(int createStatus, String createBody, String bucketBody, boolean rejected)
            throws IOException {
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/bucket", exchange -> {
            calls.add(exchange.getRequestMethod());
            respond(exchange, createStatus, createBody);
        });
        server.createContext("/storage/v1/bucket/synthetic", exchange -> {
            calls.add(exchange.getRequestMethod());
            respond(exchange, 200, bucketBody);
        });
        server.start();
        try {
            var storage = new SupabaseStorageService(properties(server), RestClient.builder());
            if (rejected) {
                assertThrows(IllegalStateException.class, storage::ensureBucketExists);
            } else {
                assertDoesNotThrow(storage::ensureBucketExists);
            }
            assertEquals(List.of("POST", "GET"), calls);
        } finally {
            server.stop(0);
        }
    }

    private SupabaseProperties properties(HttpServer server) {
        var properties = new SupabaseProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setBucketName("synthetic");
        properties.setServiceKey("synthetic");
        return properties;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, json);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
