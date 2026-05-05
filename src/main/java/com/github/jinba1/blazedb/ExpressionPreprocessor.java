package com.github.jinba1.blazedb;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.util.LinkedList;
import java.util.Stack;
import java.util.List;
/**
 * The ExpressionPreprocessor class analyzes SQL WHERE clauses to separate
 * join conditions from selection conditions, which is essential for query planning
 * and optimization.
 * This class implements the visitor pattern to traverse expression trees, identifying
 * which tables are referenced by each condition. Based on this analysis, it categorizes
 * expressions as:
 * - Join expressions: Conditions that involve columns from multiple tables
 * - Selection expressions: Conditions that involve columns from only a single table
 * This preprocessing is particularly important for constructing efficient query plans,
 * as it allows the system to:
 * 1. Identify true join conditions for join operators
 * 2. Apply selections as early as possible in the execution plan
 * 3. Support selection push-down optimization
 * The class is used during the query planning phase to analyze WHERE clauses and
 * construct appropriate operator trees.
 */
public class ExpressionPreprocessor extends ExpressionVisitorAdapter {

    private final Stack<String> tableStack; // track table references
    private final List<Expression> joinExpressions;
    private final List<Expression> selectExpressions;
    private boolean ready; // flag indicating if evaluation has been performed

    /**
     * Constructs a new ExpressionPreprocessor.
     * Initializes internal data structures for tracking table references and expressions.
     */
    public ExpressionPreprocessor() {
        tableStack = new Stack<>();
        joinExpressions = new LinkedList<>();
        selectExpressions = new LinkedList<>();
        ready = false;
    }

    /**
     * Evaluates an expression to separate join conditions from selection conditions.
     * Traverses the expression tree, identifying which conditions involve multiple tables
     * (join conditions) and which involve only a single table (selection conditions).
     * @param expression The SQL expression to evaluate, typically from a WHERE clause
     * @throws IllegalStateException If evaluate has already been called on this instance
     */
    public void evaluate(Expression expression) {
        if (ready) {
            throw new IllegalStateException("JoinsFinder has already evaluated");
        }
        tableStack.clear();
        joinExpressions.clear();
        selectExpressions.clear();

        expression.accept(this);

        ready = true;
    }

    /**
     * Returns the list of join expressions identified during evaluation.
     * These are conditions that reference columns from multiple tables and
     * should be implemented as join conditions rather than selections.
     * @return List of expressions that represent join conditions
     * @throws IllegalStateException If evaluate has not been called
     */
    public List<Expression> getJoinExpressions() {
        if (!ready) {
            throw new IllegalStateException("JoinsFinder has not been evaluated");
        }
        return joinExpressions;
    }

    /**
     * Returns the list of selection expressions identified during evaluation.
     * These are conditions that reference columns from a single table and
     * can be implemented as selection operations.
     * @return List of expressions that represent selection conditions
     * @throws IllegalStateException If evaluate has not been called
     */
    public List<Expression> getSelectExpressions() {
        if (!ready) {
            throw new IllegalStateException("JoinsFinder has not been evaluated");
        }
        return selectExpressions;
    }

    /**
     * Visits an AND expression and processes both of its operands.
     * AND expressions themselves are not categorized, but their components are.
     * @param andExpression The AND expression to visit
     */
    @Override
    public void visit(AndExpression andExpression) {
        // AND expression itself cannot be a single join expression
        andExpression.getLeftExpression().accept(this);
        andExpression.getRightExpression().accept(this);
    }

    /**
     * Visits an equals comparison expression.
     * @param equalsTo The equals comparison to visit
     */
    @Override
    public void visit(EqualsTo equalsTo) {
        visitBinaryExpression(equalsTo);
    }

    /**
     * Visits a not-equals comparison expression.
     * @param notEqualsTo The not-equals comparison to visit
     */
    @Override
    public void visit(NotEqualsTo notEqualsTo) {
        visitBinaryExpression(notEqualsTo);
    }

    /**
     * Visits a greater-than comparison expression.
     * @param greaterThan The greater-than comparison to visit
     */
    @Override
    public void visit(GreaterThan greaterThan) {
        visitBinaryExpression(greaterThan);
    }

    /**
     * Visits a greater-than-or-equals comparison expression.
     * @param greaterThanEquals The greater-than-or-equals comparison to visit
     */
    @Override
    public void visit(GreaterThanEquals greaterThanEquals) {
        visitBinaryExpression(greaterThanEquals);
    }

    /**
     * Visits a less-than comparison expression.
     * @param minorThan The less-than comparison to visit
     */
    @Override
    public void visit(MinorThan minorThan) {
        visitBinaryExpression(minorThan);
    }

    /**
     * Visits a less-than-or-equals comparison expression.
     * @param minorThanEquals The less-than-or-equals comparison to visit
     */
    @Override
    public void visit(MinorThanEquals minorThanEquals) {
        visitBinaryExpression(minorThanEquals);
    }

    /**
     * Visits a column reference and pushes its table name onto the stack.
     * Verifies that the column exists in the database schema.
     * @param column The column reference to visit
     * @throws UnsupportedOperationException If the column or table doesn't exist
     */
    @Override
    public void visit(Column column) {
        String tableName = column.getTable().getName();
        if (DBCatalog.getInstance().tableExists(tableName)) {
            String columnName = column.getColumnName();
            if (DBCatalog.getInstance().columnExists(tableName, columnName)) {
                tableStack.push(tableName);
            } else {
                throw new UnsupportedOperationException(String.format("Table %s does not have column %s", tableName, columnName));
            }
        } else {
            throw new UnsupportedOperationException(String.format("Table %s does not exist", tableName));
        }
    }

    /**
     * Visits a long value literal and pushes null onto the stack to indicate
     * this is not a table reference.
     * @param longValue The long value literal to visit
     */
    @Override
    public void visit(LongValue longValue) {
        tableStack.push(null);
    }

    /**
     * Visits a binary expression and determines if it's a join or selection condition.
     * The decision is based on whether the expression references multiple tables.
     * @param expression The binary expression to visit
     * @throws UnsupportedOperationException If the expression type is not supported
     */
    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        expression.getLeftExpression().accept(this);
        expression.getRightExpression().accept(this);

        String rightTable = tableStack.pop();
        String leftTable = tableStack.pop();

        if (expression instanceof EqualsTo || expression instanceof NotEqualsTo
        || expression instanceof GreaterThanEquals || expression instanceof GreaterThan
        || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            if ((rightTable != null && leftTable != null) && (!rightTable.equals(leftTable))) {
                joinExpressions.add(expression);
            } else {
                selectExpressions.add(expression);
            }
        } else {
            throw new UnsupportedOperationException("Unsupported binary expression: " + expression.getClass().getName());
        }

    }

}
