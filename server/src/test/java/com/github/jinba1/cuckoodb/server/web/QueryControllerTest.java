package com.github.jinba1.cuckoodb.server.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.github.jinba1.cuckoodb.server.query.ApiPrincipalResolver;
import com.github.jinba1.cuckoodb.server.query.ConcurrencyLimitExceededException;
import com.github.jinba1.cuckoodb.server.query.QueryService;
import com.github.jinba1.cuckoodb.server.query.QueryServiceResult;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for {@code POST /queries}: response JSON shape and the full ErrorCode → HTTP
 * mapping, driven by a mocked {@link QueryService} so each branch is exercised in isolation.
 */
@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private ApiPrincipalResolver principalResolver;

    @BeforeEach
    void anonymousPrincipal() {
        when(principalResolver.resolve(any())).thenReturn("anonymous");
    }

    private static String body(String sql) {
        return "{\"sql\":\"" + sql + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions postQuery(String sql) throws Exception {
        return mvc.perform(post("/queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(sql)));
    }

    private void serviceThrows(QueryExecutionException ex) {
        when(queryService.execute(any(), any(), any(), any())).thenThrow(ex);
    }

    @Test
    void executedQueryReturns200WithColumnArraysAndRowCount() throws Exception {
        QueryResultSet rs = new QueryResultSet(
                List.of(new ColumnMeta("a", "student.a", ColumnType.INT),
                        new ColumnMeta("name", "student.name", ColumnType.STRING)),
                List.of(List.<Value>of(new IntValue(1), new StringValue("alice"))),
                false, null);
        when(queryService.execute(eq("SELECT * FROM Student"), isNull(), isNull(), eq("anonymous")))
                .thenReturn(QueryServiceResult.of(rs));

        postQuery("SELECT * FROM Student")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.columns[0].name").value("a"))
                .andExpect(jsonPath("$.columns[0].qualifiedName").value("student.a"))
                .andExpect(jsonPath("$.columns[0].type").value("INT"))
                .andExpect(jsonPath("$.rows[0][0]").value(1))
                .andExpect(jsonPath("$.rows[0][1]").value("alice"))
                .andExpect(jsonPath("$.explain").doesNotExist());
    }

    @Test
    void explainReturns200WithExplainSetAndNoRows() throws Exception {
        when(queryService.execute(any(), any(), any(), any()))
                .thenReturn(QueryServiceResult.explain("=== Plan (as written) ===\nScan[Student]"));

        postQuery("EXPLAIN SELECT * FROM Student")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explain").value(org.hamcrest.Matchers.containsString("Plan")))
                .andExpect(jsonPath("$.rows").doesNotExist())
                .andExpect(jsonPath("$.columns").doesNotExist())
                .andExpect(jsonPath("$.rowCount").doesNotExist());
    }

    @Test
    void parseErrorMapsTo400() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.PARSE_ERROR, "SQL syntax error: bad"));
        postQuery("SELECT FROM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PARSE_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("syntax")));
    }

    @Test
    void unsupportedSqlMapsTo422() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL, "Only SELECT"));
        postQuery("DELETE FROM Student").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_SQL"));
    }

    @Test
    void unknownTableOnQueryPathMapsTo422() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.UNKNOWN_TABLE,
                "Table 'Nope' not found. Available tables: Student."));
        postQuery("SELECT * FROM Nope").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_TABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Available tables")));
    }

    @Test
    void unknownColumnMapsTo422() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.UNKNOWN_COLUMN,
                "Column 'z' not found in Student"));
        postQuery("SELECT Student.z FROM Student")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_COLUMN"));
    }

    @Test
    void typeMismatchMapsTo422() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.TYPE_MISMATCH, "cannot compare"));
        postQuery("SELECT * FROM Student WHERE Student.a = 'x'")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TYPE_MISMATCH"));
    }

    @Test
    void tupleBudgetMapsTo429WithRetryAfter() throws Exception {
        serviceThrows(new QueryBudgetExceededException(BudgetKind.TUPLES, "Tuple budget exceeded"));
        postQuery("SELECT * FROM Big")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("BUDGET_EXCEEDED"));
    }

    @Test
    void timeBudgetMapsTo504() throws Exception {
        serviceThrows(new QueryBudgetExceededException(BudgetKind.TIME, "Time budget exceeded"));
        postQuery("SELECT * FROM Slow")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.errorCode").value("BUDGET_EXCEEDED"));
    }

    @Test
    void concurrencySaturationMapsTo429WithRetryAfter() throws Exception {
        when(queryService.execute(any(), any(), any(), any()))
                .thenThrow(new ConcurrencyLimitExceededException("at limit"));
        postQuery("SELECT * FROM Student")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_LIMIT"));
    }

    @Test
    void queryPathDataErrorMapsTo500WithGenericScrubbedBody() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.DATA_ERROR,
                "Failed to open table 'x': /var/secret/path/data.csv broken"));
        postQuery("SELECT * FROM X")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorId").exists())
                .andExpect(jsonPath("$.message").value("Internal server error."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/var/secret"))));
    }

    @Test
    void internalErrorMapsTo500Generic() throws Exception {
        serviceThrows(new QueryExecutionException(ErrorCode.INTERNAL, "invariant broke at /home/x"));
        postQuery("SELECT * FROM X")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorId").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/home/x"))));
    }

    @Test
    void nonPositiveBudgetMapsTo400() throws Exception {
        when(queryService.execute(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("maxTuples must be a positive integer; got 0"));
        mvc.perform(post("/queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT * FROM Student\",\"maxTuples\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void malformedJsonBodyMapsTo400() throws Exception {
        mvc.perform(post("/queries").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void yamlBodyOnQueriesIsRejectedAsJsonOnly() throws Exception {
        // /queries is a JSON-only gateway; a YAML body must not be silently parsed even though
        // jackson-dataformat-yaml is on the classpath.
        mvc.perform(post("/queries").contentType(MediaType.parseMediaType("application/yaml"))
                        .content("sql: \"SELECT 1\""))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("yaml"))));
    }

    @Test
    void wrongContentTypeOnQueriesGivesEndpointSpecificHint() throws Exception {
        // The 415 handler is global, so its hint must reflect THIS endpoint's accepted types
        // (JSON for /queries), never the upload endpoint's text/csv.
        mvc.perform(post("/queries").contentType(MediaType.TEXT_PLAIN).content(body("SELECT 1")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("json")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("text/csv"))));
    }
}
