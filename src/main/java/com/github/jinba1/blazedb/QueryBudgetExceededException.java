package com.github.jinba1.blazedb;

/**
 * Thrown when a query exceeds its {@link QueryBudget} (tuples processed or wall-clock time).
 * Subclass of QueryExecutionException so existing error handling paths apply.
 */
public class QueryBudgetExceededException extends QueryExecutionException {
    public QueryBudgetExceededException(String message) {
        super(message);
    }
}
