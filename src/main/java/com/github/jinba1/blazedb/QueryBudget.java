package com.github.jinba1.blazedb;

/**
 * Per-query execution budget: caps total tuples processed across all operators and/or
 * wall-clock execution time. One instance is attached to an operator tree before
 * execution; every tuple emission anywhere in the tree charges it, so internal work
 * (e.g. a cross-product join) counts even when the final output is small.
 *
 * <p>Null limits mean unlimited. The clock starts lazily on the first {@link #charge()},
 * so the deadline reflects execution start rather than parse time.
 */
public class QueryBudget {

    private final Long maxTuples;
    private final Long timeoutMs;
    private long processed = 0;
    private long deadlineNanos;
    private boolean started = false;

    /**
     * @param maxTuples maximum tuples processed across all operators, or null for unlimited
     * @param timeoutMs maximum wall-clock execution time in milliseconds, or null for unlimited
     */
    public QueryBudget(Long maxTuples, Long timeoutMs) {
        this.maxTuples = maxTuples;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Records one processed tuple and enforces both limits.
     * @throws QueryBudgetExceededException when either limit is exceeded
     */
    public void charge() {
        if (!started) {
            started = true;
            if (timeoutMs != null) {
                deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000;
            }
        }
        processed++;
        if (maxTuples != null && processed > maxTuples) {
            throw new QueryBudgetExceededException(
                    "Tuple budget exceeded: limit " + maxTuples + ", query processed "
                            + processed + " tuples");
        }
        if (timeoutMs != null && System.nanoTime() > deadlineNanos) {
            throw new QueryBudgetExceededException(
                    "Time budget exceeded: limit " + timeoutMs + " ms");
        }
    }

    /** Total tuples charged so far. */
    public long processed() {
        return processed;
    }
}
