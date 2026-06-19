package com.github.jinba1.cuckoodb.server.web;

import static com.github.jinba1.cuckoodb.server.TestFiles.deleteRecursively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
@AutoConfigureTestRestTemplate
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
        assertEquals("Widgets", created.get("name").asString());
        assertEquals(2, created.get("rowCount").asInt());
        assertEquals("qty", created.get("columns").get(1).get("name").asString());
        assertEquals("INT", created.get("columns").get(1).get("type").asString());

        // The freshly uploaded table is immediately queryable.
        ResponseEntity<String> query = exchange("/queries", HttpMethod.POST,
                MediaType.APPLICATION_JSON, "{\"sql\":\"SELECT * FROM Widgets\"}");
        assertEquals(200, query.getStatusCode().value(), query.getBody());
        assertEquals(2, JSON.readTree(query.getBody()).get("rowCount").asInt());

        // And it is listed and describable via the catalog endpoints.
        ResponseEntity<String> describe = rest.getForEntity("/tables/Widgets", String.class);
        assertEquals(200, describe.getStatusCode().value());
        assertEquals("STRING", JSON.readTree(describe.getBody())
                .get("columns").get(0).get("type").asString(), "sku column is string-typed");
    }

    @Test
    void embeddedNewlineInQuotedFieldCountsAsOneRow() throws Exception {
        // A quoted field containing a newline is ONE CSV record per RFC4180, not two lines;
        // rowCount must agree with what SELECT COUNT(*) would scan.
        ResponseEntity<String> resp = csv("/tables/Notes", "note\n\"line1\nline2\"\n");
        assertEquals(201, resp.getStatusCode().value(), resp.getBody());
        assertEquals(1, JSON.readTree(resp.getBody()).get("rowCount").asInt(),
                "embedded-newline quoted field must count as a single data row");
    }

    @Test
    void whitespacePaddedMultilineQuotedFieldMatchesEngineRowCount() throws Exception {
        // Leading/trailing whitespace around a quoted field with an embedded newline: the engine's
        // ignoreSurroundingSpaces dialect treats this as ONE record, so rowCount must too — plain
        // RFC4180 would mis-split it into two.
        ResponseEntity<String> resp = csv("/tables/Padded", "note\n  \"l1\nl2\"  \n");
        assertEquals(201, resp.getStatusCode().value(), resp.getBody());
        assertEquals(1, JSON.readTree(resp.getBody()).get("rowCount").asInt(),
                "whitespace-padded multiline quoted field must count as one row, matching the engine");
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
        assertEquals("DATA_ERROR", JSON.readTree(raw).get("errorCode").asString());
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
}
