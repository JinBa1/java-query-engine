package com.github.jinba1.cuckoodb.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.jinba1.cuckoodb.ColumnMeta;
import com.github.jinba1.cuckoodb.IntValue;
import com.github.jinba1.cuckoodb.QueryResultSet;
import com.github.jinba1.cuckoodb.StringValue;
import com.github.jinba1.cuckoodb.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Response body for {@code POST /queries}. Rows are positional arrays aligned with
 * {@code columns} by index — chosen over row-objects because a join {@code SELECT *} can emit
 * duplicate bare names that object keys would collapse. An EXPLAIN request populates only
 * {@code explain}; an executed query leaves {@code explain} null. Null fields are omitted from
 * JSON, so the two shapes are unambiguous on the wire.
 *
 * @param columns   per-column metadata (null for EXPLAIN)
 * @param rows      positional value arrays (null for EXPLAIN)
 * @param rowCount  number of rows returned (null for EXPLAIN)
 * @param truncated whether a LIMIT cut the result short (null for EXPLAIN)
 * @param hint      how to refine when truncated; null otherwise
 * @param explain   the rendered plan text for an EXPLAIN request; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryResponse(
        List<ColumnDto> columns,
        List<List<Object>> rows,
        Long rowCount,
        Boolean truncated,
        String hint,
        String explain) {

    /** Maps an executed result set, converting typed values to JSON-native ints/strings. */
    public static QueryResponse fromResultSet(QueryResultSet rs) {
        List<ColumnDto> columns = new ArrayList<>(rs.columns().size());
        for (ColumnMeta c : rs.columns()) {
            columns.add(new ColumnDto(c.name(), c.qualifiedName(),
                    c.type() == null ? null : c.type().name()));
        }
        List<List<Object>> rows = new ArrayList<>(rs.rows().size());
        for (List<Value> row : rs.rows()) {
            List<Object> out = new ArrayList<>(row.size());
            for (Value v : row) {
                out.add(toJson(v));
            }
            rows.add(out);
        }
        return new QueryResponse(columns, rows, (long) rs.rows().size(),
                rs.truncated(), rs.hint(), null);
    }

    /** Wraps EXPLAIN plan text; columns/rows/rowCount/truncated stay null (no execution). */
    public static QueryResponse fromExplain(String explainText) {
        return new QueryResponse(null, null, null, null, null, explainText);
    }

    /**
     * An {@code int} value serializes as a JSON number, a string as a JSON string — the typed
     * distinction is intentional and deliberately lossy (an INT-typed {@code "007"} becomes 7).
     */
    private static Object toJson(Value v) {
        if (v instanceof IntValue iv) {
            return iv.v();
        }
        if (v instanceof StringValue sv) {
            return sv.v();
        }
        throw new IllegalStateException("Unknown value type: " + v.getClass().getName());
    }
}
