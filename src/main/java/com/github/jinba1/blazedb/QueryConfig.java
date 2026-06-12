package com.github.jinba1.blazedb;

/**
 * Per-query planner configuration. Replaces the former mutable static flags in
 * {@link Constants}; immutable so concurrent queries cannot affect each other.
 *
 * @param useQueryOptimization whether {@link QueryPlanOptimizer} runs on the plan
 * @param useHashJoin          whether the planner picks hash join for equi-join conditions
 */
public record QueryConfig(boolean useQueryOptimization, boolean useHashJoin) {

    /** Production defaults: optimization on, hash join on. */
    public static QueryConfig defaults() {
        return new QueryConfig(true, true);
    }
}
