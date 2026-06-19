package com.github.jinba1.cuckoodb.server.web;

import static com.github.jinba1.cuckoodb.server.TestFiles.deleteRecursively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * End-to-end gateway behaviour over a real HTTP port and a temp-dir catalog: the sync query
 * round-trip, EXPLAIN, 5xx path-scrubbing, the upload-disabled default, and concurrent queries.
 * Budget bounding has its own class ({@link BudgetIntegrationTest}); upload has
 * {@link UploadApiIntegrationTest}. Catalog isolation is by fresh context ({@code @DirtiesContext}),
 * never {@code resetDBCatalog} — the engine singleton is owned by the app's CatalogInitializer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestRestTemplate
class QueryApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    private static Path dataDir;
    private static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("cuckoo-data");
        Path data = Files.createDirectories(dataDir.resolve("data"));
        Files.writeString(data.resolve("People.csv"), "id,name\n1,alice\n2,bob\n3,carol\n");
        // A throwaway table whose backing file the 5xx test deletes to force a query-path error.
        Files.writeString(data.resolve("Doomed.csv"), "x,y\n1,2\n");
        workDir = Files.createTempDirectory("cuckoo-work");

        registry.add("cuckoodb.data-dir", () -> dataDir.toString());
        registry.add("cuckoodb.work-dir", () -> workDir.toString());
        registry.add("cuckoodb.upload.enabled", () -> "false");
        // Pin the concurrency ceiling wide enough for the 16-thread test below; otherwise it
        // defaults to availableProcessors() and the excess threads get 429 on low-CPU CI runners.
        // The 429 saturation path itself is covered by ConcurrencyLimiterTest + QueryControllerTest.
        registry.add("cuckoodb.max-concurrent-queries", () -> "32");
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteRecursively(dataDir);
        deleteRecursively(workDir);
    }

    @Test
    void syncQueryRoundTripReturnsTypedColumnArrays() throws Exception {
        JsonNode body = postQuery("{\"sql\":\"SELECT * FROM People\"}", 200);
        assertEquals(3, body.get("rowCount").asInt());
        assertFalse(body.get("truncated").asBoolean());
        assertEquals("id", body.get("columns").get(0).get("name").asString());
        assertEquals("INT", body.get("columns").get(0).get("type").asString());
        assertEquals("STRING", body.get("columns").get(1).get("type").asString());
        assertEquals(1, body.get("rows").get(0).get(0).asInt());
        assertEquals("alice", body.get("rows").get(0).get(1).asString());
    }

    @Test
    void limitMarksResultTruncated() throws Exception {
        JsonNode body = postQuery("{\"sql\":\"SELECT * FROM People LIMIT 1\"}", 200);
        assertEquals(1, body.get("rowCount").asInt());
        assertTrue(body.get("truncated").asBoolean());
        assertNotNull(body.get("hint"));
    }

    @Test
    void explainReturnsPlanWithNoRowsAndNoRowCount() throws Exception {
        JsonNode body = postQuery("{\"sql\":\"EXPLAIN SELECT * FROM People\"}", 200);
        assertTrue(body.get("explain").asString().contains("Plan (as written)"));
        assertFalse(body.has("rows"), "EXPLAIN performs no execution, so rows is absent");
        assertFalse(body.has("rowCount"));
    }

    @Test
    void unknownTableInQueryBodyIs422() throws Exception {
        JsonNode body = postQuery("{\"sql\":\"SELECT * FROM Nope\"}", 422);
        assertEquals("UNKNOWN_TABLE", body.get("errorCode").asString());
    }

    @Test
    void queryPathDataErrorReturns500WithNoFilesystemPath() throws Exception {
        // Delete a table's backing file after startup: the scan now fails with a DATA_ERROR whose
        // raw message embeds the absolute file path. The 500 body must not leak it.
        Files.deleteIfExists(dataDir.resolve("data").resolve("Doomed.csv"));

        ResponseEntity<String> resp = exchange("/queries", HttpMethod.POST,
                MediaType.APPLICATION_JSON, "{\"sql\":\"SELECT * FROM Doomed\"}");
        assertEquals(500, resp.getStatusCode().value());
        String raw = resp.getBody();
        assertNotNull(raw);
        assertFalse(raw.contains(dataDir.toString()), "5xx body leaked a filesystem path: " + raw);
        JsonNode body = JSON.readTree(raw);
        assertNotNull(body.get("errorId"), "5xx carries a correlation id");
        assertEquals("Internal server error.", body.get("message").asString());
    }

    @Test
    void uploadDisabledByDefaultReturns404() {
        ResponseEntity<String> resp = exchange("/tables/foo", HttpMethod.POST,
                MediaType.parseMediaType("text/csv"), "a,b\n1,2\n");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void concurrentQueriesAllSucceed() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<JsonNode>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> postQuery("{\"sql\":\"SELECT People.id FROM People WHERE People.id > 1\"}", 200));
            }
            List<Future<JsonNode>> results = pool.invokeAll(tasks);
            for (Future<JsonNode> f : results) {
                assertEquals(2, f.get().get("rowCount").asInt(),
                        "every concurrent query sees a consistent, isolated result");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode postQuery(String json, int expectedStatus) throws IOException {
        ResponseEntity<String> resp = exchange("/queries", HttpMethod.POST,
                MediaType.APPLICATION_JSON, json);
        assertEquals(expectedStatus, resp.getStatusCode().value(),
                "unexpected status; body=" + resp.getBody());
        return JSON.readTree(resp.getBody());
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
            MediaType contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
