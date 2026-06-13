package com.github.jinba1.cuckoodb;

import net.sf.jsqlparser.expression.Expression;

/**
 * One parsed aggregate call from a SELECT list.
 *
 * @param function  the aggregate function
 * @param argument  the inner expression; null only for COUNT(*)
 * @param schemaKey the schema/output key exactly as registered, e.g. "SUM(student.b)",
 *                  "COUNT(*)", "SUM(0)" — lowercased only at output by the catalog
 */
public record AggregateCall(AggregateFunction function, Expression argument, String schemaKey) {
}
