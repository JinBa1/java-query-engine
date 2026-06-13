package com.github.jinba1.cuckoodb;

/**
 * Thrown when a query fails for a reason the caller can act on. Messages state the
 * operation, the table/column/literal involved, and what is actually available,
 * so callers (and downstream LLM agents) can self-correct; the {@link ErrorCode}
 * classifies the failure without message parsing.
 */
public class QueryExecutionException extends RuntimeException {

    private final ErrorCode code;

    /** Uncategorized failure; defaults to {@link ErrorCode#INTERNAL}. */
    public QueryExecutionException(String message) {
        this(ErrorCode.INTERNAL, message);
    }

    /** Categorized failure; {@code code} classifies the error for programmatic handling. */
    public QueryExecutionException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** The machine-readable category of this failure. */
    public ErrorCode code() {
        return code;
    }
}
