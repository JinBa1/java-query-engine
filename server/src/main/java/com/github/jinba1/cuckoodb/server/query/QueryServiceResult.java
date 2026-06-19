package com.github.jinba1.cuckoodb.server.query;

import com.github.jinba1.cuckoodb.QueryResultSet;

/**
 * The two shapes a successful {@code QueryService} call can produce: an executed result set,
 * or EXPLAIN plan text (no execution). Exactly one field is non-null. Keeping them in one
 * return type lets the controller branch on {@link #isExplain()} without a second service call.
 *
 * @param resultSet the materialized rows for an executed query, or null for EXPLAIN
 * @param explainText the rendered plan for an EXPLAIN request, or null for an executed query
 */
public record QueryServiceResult(QueryResultSet resultSet, String explainText) {

    public static QueryServiceResult of(QueryResultSet resultSet) {
        return new QueryServiceResult(resultSet, null);
    }

    public static QueryServiceResult explain(String explainText) {
        return new QueryServiceResult(null, explainText);
    }

    public boolean isExplain() {
        return explainText != null;
    }
}
