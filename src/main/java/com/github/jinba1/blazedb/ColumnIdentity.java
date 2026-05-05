package com.github.jinba1.blazedb;

import net.sf.jsqlparser.schema.Column;

import java.util.*;

/**
 * Helper class to ensure proper column identity comparison based on logical column identity.
 * This class compares columns based on their table name and column name, rather than object identity.
 * Two columns are considered equal if they refer to the same column in the same table,
 * even if they are different Column objects.
 * This is essential for correct deduplication during projection pushdown optimization,
 * particularly when multiple operators (like ProjectOperator and SortOperator) reference
 * the same logical column with different Column instances.
 */
public class ColumnIdentity {
    private final String tableName;
    private final String columnName;

    /**
     * Constructs a ColumnIdentity instance from a Column object.
     * Extracts and stores the table name and column name (converted to lowercase for case-insensitive comparison).
     *
     * @param column The Column object to extract identity information from
     */
    public ColumnIdentity(Column column) {
        this.tableName = column.getTable().getName();
        this.columnName = column.getColumnName().toLowerCase();
    }

    /**
     * Compares this ColumnIdentity with another object for equality.
     * Two ColumnIdentity objects are equal if they have the same table name and column name.
     *
     * @param o The object to compare with
     * @return true if the objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ColumnIdentity that = (ColumnIdentity) o;
        return Objects.equals(tableName, that.tableName) &&
                Objects.equals(columnName, that.columnName);
    }

    /**
     * Returns a hash code value for this ColumnIdentity.
     * The hash code is computed based on the table name and column name.
     *
     * @return A hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(tableName, columnName);
    }
    public static Set<Column> deduplicateColumns(Collection<Column> columns) {
        // Use LinkedHashMap to maintain insertion order while deduplicating
        Map<ColumnIdentity, Column> uniqueColumns = new LinkedHashMap<>();

        for (Column column : columns) {
            uniqueColumns.put(new ColumnIdentity(column), column);
        }

        return new LinkedHashSet<>(uniqueColumns.values());

    }
}