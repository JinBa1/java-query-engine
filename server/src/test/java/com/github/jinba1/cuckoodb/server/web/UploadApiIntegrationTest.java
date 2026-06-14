package com.github.jinba1.cuckoodb.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end upload pipeline with the feature enabled and a small byte cap: a successful
 * upload-then-query, plus every rejection — oversize (413), wrong content-type (415), bad table
 * name (400), duplicate name (409), and malformed CSV (400, path-scrubbed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");

    @Autowired
    private TestRestTemplate rest;

    private static Path dataDir;
    private static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("cuckoo-data");
        Files.createDirectories(dataDir.resolve("data")); // empty seed; tables arrive via upload
        workDir = Files.createTempDirectory("cuckoo-work");

        registry.add("cuckoodb.data-dir", () -> dataDir.toString());
        registry.add("cuckoodb.work-dir", () -> workDir.toString());
        registry.add("cuckoodb.upload.enabled", () -> "true");
        registry.add("cuckoodb.upload.max-bytes", () -> "64"); // tiny, to exercise the 413 path
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteRecursively(dataDir);
        deleteRecursively(workDir);
    }

    @Test
    void uploadThenQueryRoundTrips() throws Exception {
        ResponseEntity<String> upload = csv("/tables/Widgets", "sku,qty\nA,5\nB,7\n");
        assertEquals(201, upload.getStatusCode().value(), upload.getBody());
        JsonNode created = JSON.readTree(upload.getBody());
        assertEquals("Widgets", created.get("name").asText());
        assertEquals(2, created.get("rowCount").asInt());
        assertEquals("qty", created.get("columns").get(1).get("name").asText());
        assertEquals("INT", created.get("columns").get(1).get("type").asText());

        // The freshly uploaded table is immediately queryable.
        ResponseEntity<String> query = exchange("/queries", HttpMethod.POST,
                MediaType.APPLICATION_JSON, "{\"sql\":\"SELECT * FROM Widgets\"}");
        assertEquals(200, query.getStatusCode().value(), query.getBody());
        assertEquals(2, JSON.readTree(query.getBody()).get("rowCount").asInt());

        // And it is listed and describable via the catalog endpoints.
        ResponseEntity<String> describe = rest.getForEntity("/tables/Widgets", String.class);
        assertEquals(200, describe.getStatusCode().value());
        assertEquals("STRING", JSON.readTree(describe.getBody())
                .get("columns").get(0).get("type").asText(), "sku column is string-typed");
    }

    @Test
    void oversizeUploadReturns413() {
        String big = "n\n" + "1\n".repeat(40); // ~82 bytes > 64-byte cap
        ResponseEntity<String> resp = csv("/tables/Big", big);
        assertEquals(413, resp.getStatusCode().value());
    }

    @Test
    void wrongContentTypeReturns415() {
        ResponseEntity<String> resp = exchange("/tables/Json", HttpMethod.POST,
                MediaType.APPLICATION_JSON, "sku,qty\nA,5\n");
        assertEquals(415, resp.getStatusCode().value());
    }

    @Test
    void invalidTableNameReturns400() {
        // A dotted name is a path-traversal shape; the strict charset rejects it before any path.
        ResponseEntity<String> resp = csv("/tables/a.b", "x\n1\n");
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void duplicateUploadReturns409() {
        assertEquals(201, csv("/tables/Dup", "x\n1\n").getStatusCode().value());
        assertEquals(409, csv("/tables/Dup", "x\n2\n").getStatusCode().value());
    }

    @Test
    void malformedCsvReturns400WithNoFilesystemPath() throws Exception {
        // Ragged row: header has 2 columns, the data row has 1 -> engine DATA_ERROR -> 400.
        ResponseEntity<String> resp = csv("/tables/Ragged", "a,b\n1\n");
        assertEquals(400, resp.getStatusCode().value());
        String raw = resp.getBody();
        assertNotNull(raw);
        assertFalse(raw.contains(workDir.toString()), "upload 400 leaked a work-dir path: " + raw);
        assertEquals("DATA_ERROR", JSON.readTree(raw).get("errorCode").asText());
    }

    private ResponseEntity<String> csv(String path, String body) {
        return exchange(path, HttpMethod.POST, TEXT_CSV, body);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
            MediaType contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }
}
