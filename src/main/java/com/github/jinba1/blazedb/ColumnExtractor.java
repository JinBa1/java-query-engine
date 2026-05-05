package com.github.jinba1.blazedb;

import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.List;

/**
 * The ColumnExtractor class is a utility visitor for extracting column references from expressions.
 * It extends JSqlParser's ExpressionVisitorAdapter to traverse the expression tree and collect
 * all Column objects encountered.
 * This utility is particularly useful for analyzing expressions to determine which columns are
 * referenced, which is essential for operations like:
 * - Projection push-down optimization
 * - Join condition analysis
 * - Identifying columns required for expression evaluation
 * The class follows the visitor pattern, where the expression object accepts this visitor,
 * and the visit methods are called for each expression node in the tree.
 */
public class ColumnExtractor extends ExpressionVisitorAdapter {
    // A list that collects all Column objects encountered during expression traversal.
    private final List<Column> columns = new ArrayList<>();

    /**
     * Visits a Column node in the expression tree and adds it to the collection.
     * This method is called automatically when the expression is traversed.
     * @param column The Column object encountered in the expression
     */
    @Override
    public void visit(Column column) {
        columns.add(column);
    }

    /**
     * Returns the list of all Column objects that were found during expression traversal.
     * This method should be called after the expression has accepted this visitor.
     * @return A list of all Column objects referenced in the expression
     */
    public List<Column> getColumns() {
        return columns;
    }
}
