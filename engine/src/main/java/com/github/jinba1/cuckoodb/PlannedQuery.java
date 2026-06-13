package com.github.jinba1.cuckoodb;

import com.github.jinba1.cuckoodb.operator.Operator;

/**
 * Result of planning one query file.
 *
 * @param root        the (optimized) executable operator tree; never null (planning failures throw)
 * @param explainText for EXPLAIN queries, the rendered before/after plan text; null otherwise
 */
public record PlannedQuery(Operator root, String explainText) {
}
