package com.github.jinba1.cuckoodb.server.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;

import org.junit.jupiter.api.Test;

/**
 * BudgetPolicy must never yield a null-valued budget (the engine's "null = unlimited" path is
 * then unreachable through the server) and the configured hard cap must always win over a
 * larger client request.
 */
class BudgetPolicyTest {

    /** Properties with the documented defaults: tuples 100k/1M, time 5s/30s. */
    private BudgetPolicy policy() {
        return new BudgetPolicy(new CuckooDbProperties(null, null, null, null, null));
    }

    @Test
    void nullRequestTakesDefaults() {
        BudgetPolicy.ClampedBudget budget = policy().clamp(null, null);
        assertEquals(100_000L, budget.maxTuples());
        assertEquals(5_000L, budget.timeoutMs());
    }

    @Test
    void requestAboveCapIsClampedDownToCap() {
        BudgetPolicy.ClampedBudget budget = policy().clamp(10_000_000L, 999_999L);
        assertEquals(1_000_000L, budget.maxTuples(), "tuple cap always wins");
        assertEquals(30_000L, budget.timeoutMs(), "time cap always wins");
    }

    @Test
    void requestWithinCapPassesThrough() {
        BudgetPolicy.ClampedBudget budget = policy().clamp(500L, 1_000L);
        assertEquals(500L, budget.maxTuples());
        assertEquals(1_000L, budget.timeoutMs());
    }

    @Test
    void nonPositiveRequestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy().clamp(0L, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> policy().clamp(100L, -1L));
    }
}
