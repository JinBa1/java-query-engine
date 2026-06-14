package com.github.jinba1.cuckoodb.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the fail-closed budget guarantee: the server always attaches a budget, so even
 * a client requesting an enormous tuple limit is clamped to the cap and cannot run unbounded;
 * a query that omits any budget still trips the configured default; and EXPLAIN consumes no
 * budget at all. The whole context runs with a deliberately tiny cap (2 tuples).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BudgetIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    private static Path dataDir;
    private static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("cuckoo-data");
        Path data = Files.createDirectories(dataDir.resolve("data"));
        // 4 rows: any full scan charges 4 tuples, exceeding the 2-tuple cap below.
        Files.writeString(data.resolve("People.csv"), "id\n1\n2\n3\n4\n");
        workDir = Files.createTempDirectory("cuckoo-work");

        registry.add("cuckoodb.data-dir", () -> dataDir.toString());
        registry.add("cuckoodb.work-dir", () -> workDir.toString());
        registry.add("cuckoodb.query.max-tuples-default", () -> "2");
        registry.add("cuckoodb.query.max-tuples-cap", () -> "2");
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteRecursively(dataDir);
        deleteRecursively(workDir);
    }

    @Test
    void omittedBudgetStillEnforcesTheDefault() throws Exception {
        // No maxTuples in the request: the server must still attach the default budget (2),
        // which a 4-row scan exceeds. A 200 here would mean the unbounded path was reachable.
        ResponseEntity<String> resp = postQuery("{\"sql\":\"SELECT * FROM People\"}");
        assertEquals(429, resp.getStatusCode().value(), resp.getBody());
        assertEquals("BUDGET_EXCEEDED", JSON.readTree(resp.getBody()).get("errorCode").asText());
        assertTrue(resp.getHeaders().containsKey("Retry-After"));
    }

    @Test
    void hugeClientBudgetIsClampedToCapAndStillTrips() throws Exception {
        // The client cannot buy its way past the cap: 9,999,999 clamps to 2, the scan still trips.
        ResponseEntity<String> resp = postQuery(
                "{\"sql\":\"SELECT * FROM People\",\"maxTuples\":9999999}");
        assertEquals(429, resp.getStatusCode().value(), resp.getBody());
        assertEquals("BUDGET_EXCEEDED", JSON.readTree(resp.getBody()).get("errorCode").asText());
    }

    @Test
    void explainConsumesNoBudget() throws Exception {
        // EXPLAIN performs no execution, so the tiny budget never bites: a plan comes back 200.
        ResponseEntity<String> resp = postQuery("{\"sql\":\"EXPLAIN SELECT * FROM People\"}");
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertTrue(JSON.readTree(resp.getBody()).get("explain").asText().contains("Plan"));
    }

    private ResponseEntity<String> postQuery(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/queries", new HttpEntity<>(json, headers), String.class);
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
