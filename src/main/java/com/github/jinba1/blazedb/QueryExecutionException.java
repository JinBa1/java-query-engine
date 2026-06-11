package com.github.jinba1.blazedb;

/**
 * Thrown when query execution fails for a data- or type-related reason.
 * Messages state the operation, the column/literal involved, and both types,
 * so callers (and downstream LLM agents) can self-correct.
 */
public class QueryExecutionException extends RuntimeException {
    public QueryExecutionException(String message) {
        super(message);
    }
}
