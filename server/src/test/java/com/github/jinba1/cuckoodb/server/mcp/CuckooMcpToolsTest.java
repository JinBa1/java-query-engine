package com.github.jinba1.cuckoodb.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.jinba1.cuckoodb.BudgetKind;
import com.github.jinba1.cuckoodb.ColumnMeta;
import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.ErrorCode;
import com.github.jinba1.cuckoodb.IntValue;
import com.github.jinba1.cuckoodb.QueryBudgetExceededException;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.QueryResultSet;
import com.github.jinba1.cuckoodb.StringValue;
import com.github.jinba1.cuckoodb.Value;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade;
import com.github.jinba1.cuckoodb.server.query.ConcurrencyLimitExceededException;
import com.github.jinba1.cuckoodb.server.query.QueryService;
import com.github.jinba1.cuckoodb.server.query.QueryServiceResult;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-level contract for the five MCP tools, with a mocked {@link QueryService} and
 * {@link CatalogFacade} and a real Jackson mapper. Proves the choke-point routing (every query
 * tool calls {@code QueryService.execute(..., "mcp")} and never the engine), the injection guard
 * (a bad table name is rejected before any SQL is built), the exact SQL/budget the catalog tools
 * synthesize, and the error taxonomy mapped onto {@code CallToolResult.isError(true)} text —
 * including that a 5xx-class failure is scrubbed of filesystem paths.
 */
class CuckooMcpToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private QueryService queryService;
    private CatalogFacade catalog;
    private CuckooMcpTools tools;

    @BeforeEach
    void setUp() {
        queryService = Mockito.mock(QueryService.class);
        catalog = Mockito.mock(CatalogFacade.class);
        tools = new CuckooMcpTools(queryService, catalog, JSON);
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

    private static QueryResultSet oneRow() {
        return new QueryResultSet(
                List.of(new ColumnMeta("a", "student.a", ColumnType.INT),
                        new ColumnMeta("name", "student.name", ColumnType.STRING)),
                List.of(List.<Value>of(new IntValue(1), new StringValue("alice"))),
                false, null);
    }

    // ---- list_tables ----

    @Test
    void listTablesReturnsSortedArray() {
        when(catalog.tableNames()).thenReturn(List.of("Alpha", "Beta", "Gamma"));

        CallToolResult r = tools.listTables();

        assertFalse(isError(r));
        JsonNode arr = body(r);
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertEquals("Alpha", arr.get(0).asString());
        assertEquals("Gamma", arr.get(2).asString());
        verifyNoInteractions(queryService);
    }

    @Test
    void listTablesEmptyReturnsEmptyArray() {
        when(catalog.tableNames()).thenReturn(List.of());
        CallToolResult r = tools.listTables();
        assertFalse(isError(r));
        assertEquals(0, body(r).size());
    }

    // ---- describe_table ----

    @Test
    void describeTableReturnsCatalogSchema() {
        when(catalog.columnsOf("Student")).thenReturn(Optional.of(List.of(
                new CatalogFacade.TableColumn("a", ColumnType.INT),
                new CatalogFacade.TableColumn("name", ColumnType.STRING))));

        CallToolResult r = tools.describeTable("Student");

        assertFalse(isError(r));
        JsonNode b = body(r);
        assertEquals("Student", b.get("name").asString());
        assertEquals("a", b.get("columns").get(0).get("name").asString());
        assertEquals("INT", b.get("columns").get(0).get("type").asString());
        assertEquals("STRING", b.get("columns").get(1).get("type").asString());
        verifyNoInteractions(queryService);
    }

    @Test
    void describeTableUnknownMapsToUnknownTable() {
        when(catalog.columnsOf("Nope")).thenReturn(Optional.empty());

        CallToolResult r = tools.describeTable("Nope");

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("UNKNOWN_TABLE:"), text(r));
        verify(catalog).columnsOf("Nope");
    }

    @Test
    void describeTableInvalidNameMapsToBadRequestAndNeverHitsCatalog() {
        CallToolResult r = tools.describeTable("a.b");

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("BAD_REQUEST:"), text(r));
        verify(catalog, never()).columnsOf(any());
        verifyNoInteractions(queryService);
    }

    // ---- sample_rows ----

    @Test
    void sampleRowsBuildsExactSqlBoundedByTheLimit() {
        // The LIMIT bounds the preview; the lazy engine pulls only ~n+1 tuples, so the tool passes
        // no explicit tuple budget (null = server default) rather than one that would trip on the
        // truncation-detecting pull.
        when(queryService.execute(eq("SELECT * FROM People LIMIT 5"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        CallToolResult r = tools.sampleRows("People", 5);

        assertFalse(isError(r));
        assertEquals(1, body(r).get("rowCount").asInt());
        verify(queryService).execute("SELECT * FROM People LIMIT 5", null, null, "mcp");
    }

    @Test
    void sampleRowsDefaultsLimitToTenWhenNull() {
        when(queryService.execute(eq("SELECT * FROM People LIMIT 10"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        tools.sampleRows("People", null);

        verify(queryService).execute("SELECT * FROM People LIMIT 10", null, null, "mcp");
    }

    @Test
    void sampleRowsClampsLimitToOneThousandUpperBound() {
        when(queryService.execute(eq("SELECT * FROM People LIMIT 1000"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        tools.sampleRows("People", 999_999);

        verify(queryService).execute("SELECT * FROM People LIMIT 1000", null, null, "mcp");
    }

    @Test
    void sampleRowsClampsNonPositiveLimitToOne() {
        when(queryService.execute(eq("SELECT * FROM People LIMIT 1"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        tools.sampleRows("People", 0);

        verify(queryService).execute("SELECT * FROM People LIMIT 1", null, null, "mcp");
    }

    @Test
    void sampleRowsRejectsInjectionBeforeAnyQuery() {
        // A semicolon name is blocked by the charset BEFORE it is ever interpolated into SQL.
        CallToolResult r = tools.sampleRows("foo;DROP", 10);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("BAD_REQUEST:"), text(r));
        verifyNoInteractions(queryService);
    }

    // ---- explain_query ----

    @Test
    void explainQueryPrependsExplainAndReturnsPlanText() {
        when(queryService.execute(eq("EXPLAIN SELECT * FROM People"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.explain("=== Plan (as written) ===\nScan[People]"));

        CallToolResult r = tools.explainQuery("SELECT * FROM People");

        assertFalse(isError(r));
        assertTrue(body(r).get("explain").asString().contains("Plan"));
        verify(queryService).execute("EXPLAIN SELECT * FROM People", null, null, "mcp");
    }

    // ---- query ----

    @Test
    void querySerializesTypedColumnArrays() {
        when(queryService.execute(eq("SELECT * FROM Student"), isNull(), isNull(), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        CallToolResult r = tools.query("SELECT * FROM Student", null, null);

        assertFalse(isError(r));
        JsonNode b = body(r);
        assertEquals(1, b.get("rowCount").asInt());
        assertEquals(false, b.get("truncated").asBoolean());
        assertEquals("a", b.get("columns").get(0).get("name").asString());
        assertEquals(1, b.get("rows").get(0).get(0).asInt());
        assertEquals("alice", b.get("rows").get(0).get(1).asString());
    }

    @Test
    void queryPassesClientBudgetThroughToTheChokePoint() {
        when(queryService.execute(eq("SELECT * FROM Student"), eq(50L), eq(2000L), eq("mcp")))
                .thenReturn(QueryServiceResult.of(oneRow()));

        tools.query("SELECT * FROM Student", 50L, 2000L);

        verify(queryService).execute("SELECT * FROM Student", 50L, 2000L, "mcp");
    }

    @Test
    void queryReturnsPlanWhenCallerPrefixedExplain() {
        // Defensive: a caller who routes EXPLAIN through `query` still gets the plan shape back.
        when(queryService.execute(any(), any(), any(), eq("mcp")))
                .thenReturn(QueryServiceResult.explain("=== Plan (as written) ===\nScan[Student]"));

        CallToolResult r = tools.query("EXPLAIN SELECT * FROM Student", null, null);

        assertFalse(isError(r));
        assertTrue(body(r).get("explain").asString().contains("Plan"));
    }

    // ---- error taxonomy ----

    @Test
    void tupleBudgetMapsToBudgetExceeded() {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new QueryBudgetExceededException(BudgetKind.TUPLES, "Tuple budget exceeded"));

        CallToolResult r = tools.query("SELECT * FROM Big", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("BUDGET_EXCEEDED:"), text(r));
    }

    @Test
    void timeBudgetMapsToBudgetExceededTimeout() {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new QueryBudgetExceededException(BudgetKind.TIME, "Time budget exceeded"));

        CallToolResult r = tools.query("SELECT * FROM Slow", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("BUDGET_EXCEEDED (timeout):"), text(r));
    }

    @Test
    void unknownTableOnQueryPathIsVerbatim() {
        when(queryService.execute(any(), any(), any(), any())).thenThrow(
                new QueryExecutionException(ErrorCode.UNKNOWN_TABLE,
                        "Table 'Nope' not found. Available tables: Student."));

        CallToolResult r = tools.query("SELECT * FROM Nope", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("UNKNOWN_TABLE:"), text(r));
        assertTrue(text(r).contains("Available tables"), text(r));
    }

    @Test
    void parseErrorIsVerbatim() {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new QueryExecutionException(ErrorCode.PARSE_ERROR, "SQL syntax error: bad"));

        CallToolResult r = tools.query("SELECT FROM", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("PARSE_ERROR:"), text(r));
    }

    @Test
    void dataErrorIsScrubbedOfFilesystemPaths() {
        when(queryService.execute(any(), any(), any(), any())).thenThrow(
                new QueryExecutionException(ErrorCode.DATA_ERROR,
                        "Failed to open table 'x': /var/secret/path/data.csv broken"));

        CallToolResult r = tools.query("SELECT * FROM X", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("INTERNAL:"), text(r));
        assertTrue(text(r).contains("errorId="), text(r));
        assertFalse(text(r).contains("/var/secret"), "data-error text leaked a filesystem path: " + text(r));
    }

    @Test
    void internalErrorIsScrubbed() {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new QueryExecutionException(ErrorCode.INTERNAL, "invariant broke at /home/x"));

        CallToolResult r = tools.query("SELECT * FROM X", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("INTERNAL:"), text(r));
        assertFalse(text(r).contains("/home/x"), text(r));
    }

    @Test
    void concurrencyLimitMaps() {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new ConcurrencyLimitExceededException("at limit"));

        CallToolResult r = tools.query("SELECT * FROM Student", null, null);

        assertTrue(isError(r));
        assertTrue(text(r).startsWith("CONCURRENCY_LIMIT:"), text(r));
    }
}
