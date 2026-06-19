package com.github.jinba1.cuckoodb.server.query;

/**
 * Thrown when the query concurrency semaphore is saturated. Maps to HTTP 429 with a
 * {@code Retry-After} header — the condition is transient, so the client should retry shortly.
 */
public class ConcurrencyLimitExceededException extends RuntimeException {
    public ConcurrencyLimitExceededException(String message) {
        super(message);
    }
}
