package com.github.jinba1.cuckoodb;

/**
 * Thrown when a query exceeds its {@link QueryBudget} (tuples processed or wall-clock time).
 * Subclass of QueryExecutionException so existing error handling paths apply.
 */
public class QueryBudgetExceededException extends QueryExecutionException {

    private final BudgetKind kind;

    /**
     * @param kind    which limit was breached (tuples vs time); lets a gateway map the same
     *                engine exception to different HTTP statuses without parsing the message
     * @param message which limit was exceeded and by how much; the error code is
     *                always {@link ErrorCode#BUDGET_EXCEEDED}
     */
    public QueryBudgetExceededException(BudgetKind kind, String message) {
        super(ErrorCode.BUDGET_EXCEEDED, message);
        this.kind = kind;
    }

    /** Which budget limit was breached. */
    public BudgetKind kind() {
        return kind;
    }
}
