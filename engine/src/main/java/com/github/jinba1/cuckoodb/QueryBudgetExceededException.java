package com.github.jinba1.cuckoodb;

/**
 * Thrown when a query exceeds its {@link QueryBudget} (tuples processed or wall-clock time).
 * Subclass of QueryExecutionException so existing error handling paths apply.
 */
public class QueryBudgetExceededException extends QueryExecutionException {
    /**
     * @param message which limit was exceeded and by how much; the error code is
     *                always {@link ErrorCode#BUDGET_EXCEEDED}
     */
    public QueryBudgetExceededException(String message) {
        super(ErrorCode.BUDGET_EXCEEDED, message);
    }
}
