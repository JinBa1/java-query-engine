package com.github.jinba1.cuckoodb;

/**
 * Describes one output column of a {@link QueryResultSet}, in result-column order.
 *
 * <p>Result rows are strictly positional and column names are <em>not</em> unique — a
 * join {@code SELECT *} can emit the same bare name twice (e.g. two {@code a} columns).
 * {@code qualifiedName} disambiguates those: it is the dotted schema origin
 * (e.g. {@code student.a} vs {@code enrolled.a}) when one exists, and {@code null} for
 * aggregate/computed columns and for single-table scans whose schema carries no table
 * prefix. Clients must address columns by position, not by name.
 *
 * <p>{@code type} is best-effort, inferred from the first row's runtime value, so it is
 * {@code null} for an empty result (no row to infer from). The authoritative typed schema
 * for a base table comes from the catalog, not this field.
 *
 * @param name          the bare header name (table prefix stripped; aggregate keys kept whole)
 * @param qualifiedName the dotted schema origin, or null when there is none
 * @param type          the inferred column type, or null when the result is empty
 */
public record ColumnMeta(String name, String qualifiedName, ColumnType type) {
}
