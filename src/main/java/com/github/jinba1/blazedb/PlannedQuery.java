package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.Operator;

/**
 * Result of planning one query file.
 *
 * @param root        the (optimized) executable operator tree, or null if planning failed
 * @param explainText for EXPLAIN queries, the rendered before/after plan text; null otherwise
 */
public record PlannedQuery(Operator root, String explainText) {
}
