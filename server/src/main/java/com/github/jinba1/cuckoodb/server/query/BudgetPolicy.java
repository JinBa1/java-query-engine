package com.github.jinba1.cuckoodb.server.query;

import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;

import org.springframework.stereotype.Component;

/**
 * Turns an optional client budget request into a concrete, always-present budget: each
 * dimension defaults when the client omits it and is clamped down to the configured hard cap
 * (the cap always wins). The result is never null and never has a null dimension, so the
 * engine's "null = unlimited" path is unreachable through the server — the fail-closed
 * guarantee. This is also the seam where future per-principal caps will apply.
 */
@Component
public class BudgetPolicy {

    private final CuckooDbProperties.Query query;

    public BudgetPolicy(CuckooDbProperties properties) {
        this.query = properties.query();
    }

    /**
     * @param requestedMaxTuples client's tuple bound, or null to take the default
     * @param requestedTimeoutMs client's time bound, or null to take the default
     * @return a fully-populated budget, both dimensions non-null and within the hard caps
     * @throws IllegalArgumentException if a client supplies a non-positive bound
     */
    public ClampedBudget clamp(Long requestedMaxTuples, Long requestedTimeoutMs) {
        long maxTuples = clampDimension(requestedMaxTuples,
                query.maxTuplesDefault(), query.maxTuplesCap(), "maxTuples");
        long timeoutMs = clampDimension(requestedTimeoutMs,
                query.timeoutMsDefault(), query.timeoutMsCap(), "timeoutMs");
        return new ClampedBudget(maxTuples, timeoutMs);
    }

    private static long clampDimension(Long requested, long fallback, long cap, String name) {
        if (requested == null) {
            return fallback;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException(
                    name + " must be a positive integer; got " + requested);
        }
        return Math.min(requested, cap);
    }

    /** A concrete budget with both dimensions resolved and bounded; never null-valued. */
    public record ClampedBudget(long maxTuples, long timeoutMs) {
    }
}
