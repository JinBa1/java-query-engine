package com.github.jinba1.cuckoodb.server.mcp;

import com.github.jinba1.cuckoodb.BudgetKind;
import com.github.jinba1.cuckoodb.ErrorCode;
import com.github.jinba1.cuckoodb.QueryBudgetExceededException;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade;
import com.github.jinba1.cuckoodb.server.catalog.CatalogMapper;
import com.github.jinba1.cuckoodb.server.catalog.TableNameValidator;
import com.github.jinba1.cuckoodb.server.query.ConcurrencyLimitExceededException;
import com.github.jinba1.cuckoodb.server.query.QueryService;
import com.github.jinba1.cuckoodb.server.query.QueryServiceResult;
import com.github.jinba1.cuckoodb.server.web.dto.QueryResponse;
import com.github.jinba1.cuckoodb.server.web.dto.TableSchemaResponse;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * The MCP tool surface over the query gateway: five agent-ergonomic tools that let an agent
 * discover tables, inspect schemas, preview data and query cost, and run read-only SQL.
 *
 * <p><strong>Choke-point rule.</strong> Every query-executing tool routes through
 * {@link QueryService#execute}, and every catalog tool through {@link CatalogFacade}; no tool ever
 * touches the engine directly. MCP traffic therefore inherits the server's budget clamping,
 * concurrency permit, and audit trail for free — and per-key governance, when it lands inside
 * {@code QueryService}, is inherited retroactively with no change to any tool signature. A tool
 * that bypasses the choke point is a defect.
 *
 * <p>Auto-registered by the Spring AI annotation scanner because it is a bare {@code @Component}
 * holding {@code @McpTool} methods — deliberately <em>no</em> {@code ToolCallbackProvider} bean
 * (that is the legacy {@code @Tool} path and would fail startup here). Tools run on the SYNC
 * WebMvc Streamable-HTTP transport, so each returns a non-reactive {@link CallToolResult}.
 *
 * <p>Errors mirror the REST {@code errorCode} taxonomy as a {@code CODE: message} prefix on an
 * {@code isError(true)} result. Failures are caught and translated explicitly rather than left to
 * the framework's exception auto-conversion, so a 5xx-class fault is scrubbed of filesystem paths
 * exactly as {@code GlobalExceptionHandler} does for the REST surface.
 */
@Component
public class CuckooMcpTools {

    private static final Logger log = LoggerFactory.getLogger(CuckooMcpTools.class);

    /** Audit label for all MCP traffic; swapped for a per-principal resolver in a later phase. */
    private static final String MCP_PRINCIPAL = "mcp";

    /** sample_rows bounds: a small preview by default, never an unbounded scan. */
    private static final int SAMPLE_DEFAULT = 10;
    private static final int SAMPLE_MIN = 1;
    private static final int SAMPLE_MAX = 1000;

    private final QueryService queryService;
    private final CatalogFacade catalog;
    private final ObjectMapper objectMapper;

    public CuckooMcpTools(QueryService queryService, CatalogFacade catalog,
            ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_tables",
            description = "List all table names available in the database, sorted alphabetically. "
                    + "Call this first to discover what can be queried.")
    public CallToolResult listTables() {
        try {
            return json(catalog.tableNames());
        } catch (Exception e) {
            return translate(e);
        }
    }

    @McpTool(name = "describe_table",
            description = "Return the column schema (each column's name and type, INT or STRING) of "
                    + "one table, so a query can be written against real columns.")
    public CallToolResult describeTable(
            @McpToolParam(description = "Exact table name (case-sensitive), as returned by list_tables.",
                    required = true) String name) {
        try {
            TableNameValidator.validate(name);
            List<CatalogFacade.TableColumn> columns = catalog.columnsOf(name)
                    .orElseThrow(() -> new QueryExecutionException(
                            ErrorCode.UNKNOWN_TABLE, "Table '" + name + "' not found."));
            return json(new TableSchemaResponse(name, CatalogMapper.toDto(columns)));
        } catch (Exception e) {
            return translate(e);
        }
    }

    @McpTool(name = "sample_rows",
            description = "Return up to `limit` rows from a table as a quick data preview, without "
                    + "writing SQL. Use to inspect real values before querying.")
    public CallToolResult sampleRows(
            @McpToolParam(description = "Exact table name (case-sensitive), as returned by list_tables.",
                    required = true) String name,
            @McpToolParam(description = "Maximum rows to return (1-1000, default 10).",
                    required = false) Integer limit) {
        try {
            // Validate BEFORE interpolation: the name is the only untrusted token in the SQL, so the
            // charset check is the injection guard. A bad name never reaches the engine.
            TableNameValidator.validate(name);
            int n = clampLimit(limit);
            String sql = "SELECT * FROM " + name + " LIMIT " + n;
            // The LIMIT bounds the preview, and the engine scans lazily (it pulls only ~n+1 tuples,
            // one extra to set the truncation flag), so no explicit tuple budget is needed — and a
            // budget hugging n would trip on that truncation-detecting pull. Pass null to take the
            // server's default budget as the backstop, exactly as a normal LIMIT query does.
            QueryServiceResult result = queryService.execute(sql, null, null, MCP_PRINCIPAL);
            return json(QueryResponse.fromResultSet(result.resultSet()));
        } catch (Exception e) {
            return translate(e);
        }
    }

    @McpTool(name = "explain_query",
            description = "Return the query plan for a read-only SELECT without executing it or "
                    + "consuming any row budget. Use to preview a query's cost and shape before running it.")
    public CallToolResult explainQuery(
            @McpToolParam(description = "A read-only SELECT statement (do NOT prefix it with EXPLAIN).",
                    required = true) String sql) {
        try {
            // EXPLAIN returns the plan before any budget clamp, so null bounds are correct.
            QueryServiceResult result = queryService.execute("EXPLAIN " + sql, null, null, MCP_PRINCIPAL);
            return json(QueryResponse.fromExplain(result.explainText()));
        } catch (Exception e) {
            return translate(e);
        }
    }

    @McpTool(name = "query",
            description = "Execute a read-only SELECT and return typed results as column metadata plus "
                    + "positional row arrays. Results are budget-bounded: a too-large result returns a "
                    + "BUDGET_EXCEEDED error (retry with a tighter LIMIT), a too-slow one a timeout.")
    public CallToolResult query(
            @McpToolParam(description = "A read-only SELECT statement.", required = true) String sql,
            @McpToolParam(description = "Optional cap on rows scanned before aborting; clamped to the "
                    + "server cap. Omit to take the server default.", required = false) Long maxTuples,
            @McpToolParam(description = "Optional wall-clock budget in milliseconds; clamped to the "
                    + "server cap. Omit to take the server default.", required = false) Long timeoutMs) {
        try {
            QueryServiceResult result = queryService.execute(sql, maxTuples, timeoutMs, MCP_PRINCIPAL);
            // Defensive: a caller who prefixed EXPLAIN still gets the plan shape rather than an error.
            return result.isExplain()
                    ? json(QueryResponse.fromExplain(result.explainText()))
                    : json(QueryResponse.fromResultSet(result.resultSet()));
        } catch (Exception e) {
            return translate(e);
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return SAMPLE_DEFAULT;
        }
        return Math.max(SAMPLE_MIN, Math.min(SAMPLE_MAX, limit));
    }

    /** Serializes a success payload to JSON text content via the injected (Jackson 3) mapper. */
    private CallToolResult json(Object payload) {
        return CallToolResult.builder()
                .addTextContent(objectMapper.writeValueAsString(payload))
                .build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().isError(true).addTextContent(text).build();
    }

    /**
     * Maps a failure onto the REST {@code errorCode} taxonomy as an {@code isError(true)} text
     * result. Client-actionable engine errors are returned verbatim (agent-legible, no paths);
     * a 5xx-class fault is logged with a correlation id server-side and scrubbed on the wire.
     * Audit is not duplicated here — {@code QueryService.runPlanned} already records the error
     * before rethrowing; this only logs the scrubbed cases.
     */
    private CallToolResult translate(Exception e) {
        if (e instanceof QueryBudgetExceededException be) {
            String prefix = be.kind() == BudgetKind.TIME ? "BUDGET_EXCEEDED (timeout)" : "BUDGET_EXCEEDED";
            return error(prefix + ": " + be.getMessage());
        }
        if (e instanceof QueryExecutionException qe) {
            return switch (qe.code()) {
                case PARSE_ERROR, UNSUPPORTED_SQL, UNKNOWN_TABLE, UNKNOWN_COLUMN, TYPE_MISMATCH ->
                        error(qe.code().name() + ": " + qe.getMessage());
                case BUDGET_EXCEEDED -> error("BUDGET_EXCEEDED: " + qe.getMessage());
                case DATA_ERROR, INTERNAL -> scrubbed(qe.code(), qe);
            };
        }
        if (e instanceof ConcurrencyLimitExceededException) {
            return error("CONCURRENCY_LIMIT: " + e.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return error("BAD_REQUEST: " + e.getMessage());
        }
        return scrubbed(ErrorCode.INTERNAL, e);
    }

    private CallToolResult scrubbed(ErrorCode code, Exception e) {
        String errorId = UUID.randomUUID().toString();
        log.error("MCP 5xx [{}] errorId={}", code, errorId, e);
        return error("INTERNAL: Internal server error. errorId=" + errorId);
    }
}
