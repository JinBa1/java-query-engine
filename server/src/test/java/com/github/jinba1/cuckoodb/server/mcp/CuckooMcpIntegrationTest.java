package com.github.jinba1.cuckoodb.server.mcp;

import static com.github.jinba1.cuckoodb.server.TestFiles.deleteRecursively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end proof of the MCP tool layer over a real Spring context and a temp-dir catalog. Two
 * halves: (1) the autowired {@link CuckooMcpTools} is driven directly to prove choke-point routing
 * — real {@code QueryService} (so the budget clamp bites) and real {@code CatalogFacade}; (2) one
 * wire test drives the live {@code /mcp} Streamable-HTTP endpoint with the MCP SDK client to prove
 * the bare {@code @Component} + {@code @McpTool} methods register with no provider bean and round-trip.
 *
 * <p>Catalog isolation is by fresh context ({@code @DirtiesContext}), never {@code resetDBCatalog}:
 * the engine singleton is owned by the app's {@code CatalogInitializer}, and a stray reset would
 * wipe the catalog mid-context and break sibling tests in the same Surefire run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CuckooMcpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CuckooMcpTools tools;

    @LocalServerPort
    private int port;

    private static Path dataDir;
    private static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("cuckoo-mcp-data");
        Path data = Files.createDirectories(dataDir.resolve("data"));
        Files.writeString(data.resolve("People.csv"), "id,name\n1,alice\n2,bob\n3,carol\n");
        workDir = Files.createTempDirectory("cuckoo-mcp-work");

        registry.add("cuckoodb.data-dir", () -> dataDir.toString());
        registry.add("cuckoodb.work-dir", () -> workDir.toString());
        registry.add("cuckoodb.upload.enabled", () -> "false");
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteRecursively(dataDir);
        deleteRecursively(workDir);
    }

    private static boolean isError(CallToolResult r) {
        return Boolean.TRUE.equals(r.isError());
    }

    private static String text(CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    private JsonNode body(CallToolResult r) {
        return JSON.readTree(text(r));
    }

    // ---- (1) direct-call choke-point proofs ----

    @Test
    void listTablesSeesTheSeededCatalog() {
        JsonNode arr = body(tools.listTables());
        assertTrue(arr.isArray());
        assertEquals("People", arr.get(0).asString());
    }

    @Test
    void describeTableReturnsCatalogAuthoritativeTypes() {
        JsonNode b = body(tools.describeTable("People"));
        assertEquals("People", b.get("name").asString());
        assertEquals("id", b.get("columns").get(0).get("name").asString());
        assertEquals("INT", b.get("columns").get(0).get("type").asString());
        assertEquals("STRING", b.get("columns").get(1).get("type").asString());
    }

    @Test
    void queryExecutesRealSqlReturningTypedRows() {
        JsonNode b = body(tools.query("SELECT * FROM People", null, null));
        assertEquals(3, b.get("rowCount").asInt());
        assertEquals(1, b.get("rows").get(0).get(0).asInt());
        assertEquals("alice", b.get("rows").get(0).get(1).asString());
    }

    @Test
    void sampleRowsPreviewsAndMarksTruncation() {
        JsonNode b = body(tools.sampleRows("People", 2));
        assertEquals(2, b.get("rowCount").asInt());
        assertTrue(b.get("truncated").asBoolean(), "a 2-row preview of a 3-row table is truncated");
    }

    @Test
    void explainQueryReturnsAPlanAndExecutesNothing() {
        JsonNode b = body(tools.explainQuery("SELECT * FROM People"));
        assertTrue(b.get("explain").asString().contains("Plan"));
        assertFalse(b.has("rows"), "EXPLAIN performs no execution");
    }

    @Test
    void tinyPerCallBudgetTripsBudgetExceededThroughTheChokePoint() {
        // maxTuples=1 on a 3-row scan must trip the budget — proving MCP query inherits the exact
        // BudgetPolicy enforcement the REST path gets, with no MCP-specific budget code.
        CallToolResult r = tools.query("SELECT * FROM People", 1L, null);
        assertTrue(isError(r));
        assertTrue(text(r).startsWith("BUDGET_EXCEEDED"), text(r));
    }

    @Test
    void unknownTableMapsToUnknownTable() {
        assertTrue(text(tools.describeTable("Nope")).startsWith("UNKNOWN_TABLE:"));
        assertTrue(text(tools.query("SELECT * FROM Nope", null, null)).startsWith("UNKNOWN_TABLE:"));
    }

    // ---- (2) live /mcp wire test ----

    @Test
    void mcpEndpointRegistersAllToolsAndRoundTrips() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(20))
                .build()) {
            client.initialize();

            // Registration: the bare @Component's five @McpTool methods are all discovered by the
            // annotation scanner — no MethodToolCallbackProvider/ToolCallbackProvider bean exists.
            List<String> toolNames = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertTrue(toolNames.containsAll(List.of(
                    "list_tables", "describe_table", "sample_rows", "explain_query", "query")),
                    "registered tools: " + toolNames);

            // Success path over the wire.
            CallToolResult listed = client.callTool(
                    CallToolRequest.builder("list_tables").arguments(Map.of()).build());
            assertFalse(isError(listed));
            assertTrue(text(listed).contains("People"), text(listed));

            // Error path over the wire: a bad SQL surfaces as an MCP tool error, not a transport fault.
            CallToolResult bad = client.callTool(
                    CallToolRequest.builder("query").arguments(Map.of("sql", "SELECT FROM")).build());
            assertTrue(isError(bad));
            assertTrue(text(bad).startsWith("PARSE_ERROR:"), text(bad));
        }
    }
}
