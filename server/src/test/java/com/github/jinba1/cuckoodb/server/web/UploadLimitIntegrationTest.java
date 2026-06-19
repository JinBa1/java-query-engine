package com.github.jinba1.cuckoodb.server.web;

import static com.github.jinba1.cuckoodb.server.TestFiles.deleteRecursively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * Proves the process-wide table-count cap returns 507 with no eviction. Runs with the cap
 * set to 1 so the second upload is over the ceiling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadLimitIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");

    @Autowired
    private TestRestTemplate rest;

    private static Path dataDir;
    private static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("cuckoo-data");
        Files.createDirectories(dataDir.resolve("data"));
        workDir = Files.createTempDirectory("cuckoo-work");

        registry.add("cuckoodb.data-dir", () -> dataDir.toString());
        registry.add("cuckoodb.work-dir", () -> workDir.toString());
        registry.add("cuckoodb.upload.enabled", () -> "true");
        registry.add("cuckoodb.upload.max-tables", () -> "1");
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteRecursively(dataDir);
        deleteRecursively(workDir);
    }

    @Test
    void uploadOverTableCapReturns507() throws Exception {
        assertEquals(201, csv("/tables/First", "x\n1\n").getStatusCode().value());

        ResponseEntity<String> overCap = csv("/tables/Second", "x\n1\n");
        assertEquals(507, overCap.getStatusCode().value(), overCap.getBody());
        assertEquals("TABLE_LIMIT", JSON.readTree(overCap.getBody()).get("errorCode").asText());
        // No eviction: 507 is not retryable, so it carries no Retry-After.
        assertFalse(overCap.getHeaders().containsKey("Retry-After"));
    }

    private ResponseEntity<String> csv(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_CSV);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
