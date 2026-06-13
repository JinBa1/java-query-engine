package com.github.jinba1.blazedb;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.util.Map;
import java.util.Stack;

/**
 * The ExpressionEvaluator class evaluates SQL expressions against tuples during query execution.
 * This class is responsible for determining:
 * 1. Whether a tuple satisfies a condition (for selection and join operations)
 * 2. The value of an expression (for aggregation and projection operations)
 * It implements the visitor pattern to traverse expression trees, handling various
 * expression types such as comparisons, logical operators, column references, and literals.
 * The evaluator resolves column references using schema information and then applies
 * the appropriate operators to compute results.
 * This class supports both boolean evaluation for filtering operations and numeric
 * evaluation for aggregate calculations. It maintains internal stacks to track
 * intermediate results during expression tree traversal.
 */
public class ExpressionEvaluator extends ExpressionVisitorAdapter {

    private Tuple currentTuple;
    private final Stack<Boolean> resultStack;
    private final Stack<Value> valueStack;

    private final String schemaId;
    private final PlanContext ctx;

    /**
     * Constructs an ExpressionEvaluator with the specified schema identifier.
     * The schema identifier is used to resolve column references to their positions
     * in the tuple being evaluated.
     * @param ctx The per-query context for schema resolution
     * @param schemaId The schema identifier for column resolution
     */
    public ExpressionEvaluator(PlanContext ctx, String schemaId) {
        resultStack = new Stack<>();
        valueStack = new Stack<>();
        this.ctx = ctx;
        this.schemaId = schemaId;
    }
    /**
     * Evaluates an expression to determine if a tuple satisfies the condition.
     * Traverses the expression tree, resolving column references and applying
     * operators to compute a boolean result.
     * @param expression The expression to evaluate (e.g., column > value)
     * @param tuple The tuple to evaluate against
     * @return true if the tuple satisfies the condition, false otherwise
     * @throws RuntimeException If the expression evaluation fails
     */
    public boolean evaluate(Expression expression, Tuple tuple) {

        this.currentTuple = tuple;
        this.resultStack.clear();
        this.valueStack.clear();

        expression.accept(this);

        if (resultStack.isEmpty()) {
            throw new RuntimeException("Expression evaluation did not produce a result");
        }

        boolean result = resultStack.peek();

        return resultStack.pop();
    }

    /**
     * Evaluates an expression to determine its numeric value for a tuple.
     * Used for computing aggregate values and expression results.
     * @param expression The expression to evaluate (e.g., column or column*constant)
     * @param tuple The tuple to evaluate against
     * @return The numeric result of evaluating the expression
     * @throws RuntimeException If the expression evaluation does not produce a value
     */
    public Value evaluateValue(Expression expression, Tuple tuple) {
        // Note: logical expressions should not be passed to this method; guard if needed
        this.currentTuple = tuple;
        this.resultStack.clear();
        this.valueStack.clear();

        expression.accept(this);

        if (valueStack.isEmpty()) {
            throw new RuntimeException("Expression evaluation did not produce a value");
        }

        return valueStack.pop();
    }

    /**
     * Visits an AND expression and evaluates both operands.
     * Uses short-circuit evaluation: if the left operand is false,
     * the right operand is not evaluated.
     * @param andExpression The AND expression to evaluate
     */
    @Override
    public void visit(AndExpression andExpression) {
        andExpression.getLeftExpression().accept(this);
        boolean leftResult = resultStack.pop();

        if (!leftResult) {
            resultStack.push(false);
            return;
        }

        andExpression.getRightExpression().accept(this);
        boolean rightResult = resultStack.pop();

        resultStack.push(rightResult); // left is always true at this point
    }

    /**
     * Visits an equals comparison expression.
     * @param equalsTo The equals comparison to evaluate
     */
    @Override
    public void visit(EqualsTo equalsTo) {
        visitBinaryExpression(equalsTo);
    }

    /**
     * Visits a not-equals comparison expression.
     * @param notEqualsTo The not-equals comparison to evaluate
     */
    @Override
    public void visit(NotEqualsTo notEqualsTo) {
        visitBinaryExpression(notEqualsTo);
    }

    /**
     * Visits a greater-than comparison expression.
     * @param greaterThan The greater-than comparison to evaluate
     */
    @Override
    public void visit(GreaterThan greaterThan) {
        visitBinaryExpression(greaterThan);
    }

    /**
     * Visits a greater-than-or-equals comparison expression.
     * @param greaterThanEquals The greater-than-or-equals comparison to evaluate
     */
    @Override
    public void visit(GreaterThanEquals greaterThanEquals) {
        visitBinaryExpression(greaterThanEquals);
    }

    /**
     * Visits a less-than comparison expression.
     * @param minorThan The less-than comparison to evaluate
     */
    @Override
    public void visit(MinorThan minorThan) {
        visitBinaryExpression(minorThan);
    }

    /**
     * Visits a less-than-or-equals comparison expression.
     * @param minorThanEquals The less-than-or-equals comparison to evaluate
     */
    @Override
    public void visit(MinorThanEquals minorThanEquals) {
        visitBinaryExpression(minorThanEquals);
    }

    /**
     * Visits a column reference and resolves it to a value in the current tuple.
     * Uses schema information to determine the column's position in the tuple.
     * @param column The column reference to resolve
     * @throws RuntimeException If the column cannot be resolved
     */
    @Override
    public void visit(Column column) {
        String tableName = column.getTable().getName();
        String columnName = column.getColumnName();

        Integer colIdx = ctx.resolveColumnWithOrigins(schemaId, tableName, columnName);
//                " in schema " + schemaId + ", resolved to index: " + colIdx);
//                ", tuple: " + currentTuple);

        if (colIdx == null) {
            // If not found, try looking for the column by its name only
            // Get all keys in the schema
            Map<String, Integer> schema = ctx.getSchema(schemaId);
            if (schema != null) {
                // Try to find any key that ends with ".columnName"
                String columnNameLower = columnName.toLowerCase();
                for (Map.Entry<String, Integer> entry : schema.entrySet()) {
                    String key = entry.getKey();
                    if (key.endsWith("." + columnNameLower)) {
                        colIdx = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (colIdx == null) {
            throw new QueryExecutionException(ErrorCode.UNKNOWN_COLUMN,
                    "Column '" + tableName + "." + columnName + "' not found. Available: "
                    + ctx.availableColumns(schemaId) + ".");
        }

        valueStack.push(currentTuple.getAttribute(colIdx));
    }

    /**
     * Visits a long literal value and pushes it onto the value stack.
     * @param longValue The long literal to evaluate
     */
    @Override
    public void visit(LongValue longValue) {
        valueStack.push(new IntValue((int) longValue.getValue()));
    }

    /**
     * Visits a string literal and pushes it onto the value stack.
     * @param stringValue The string literal to evaluate
     */
    @Override
    public void visit(net.sf.jsqlparser.expression.StringValue stringValue) {
        valueStack.push(new StringValue(stringValue.getValue()));
    }

    /**
     * Visits a binary expression and evaluates it based on its specific type.
     * Handles comparison operators (=, !=, >, >=, <, <=) and multiplication.
     * Arithmetic operators require int operands; comparisons require matching types.
     * @param expression The binary expression to evaluate
     * @throws UnsupportedOperationException If the expression type is not supported
     */
    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        // Reject before evaluating children: an unsupported operator (e.g. OR) has
        // boolean operands that push nothing onto the value stack, so popping below
        // would fail with an opaque EmptyStackException instead of this message
        if (!(expression instanceof Multiplication
                || expression instanceof EqualsTo || expression instanceof NotEqualsTo
                || expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals)) {
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "Unsupported condition '" + expression
                    + "'; supported comparators: =, !=, <, <=, >, >= combined with AND");
        }

        expression.getLeftExpression().accept(this);
        expression.getRightExpression().accept(this);

        // reverse order for LIFO
        Value right = valueStack.pop();
        Value left = valueStack.pop();

        if (expression instanceof Multiplication) {
            if (!(left instanceof IntValue l) || !(right instanceof IntValue r)) {
                throw new QueryExecutionException(ErrorCode.TYPE_MISMATCH,
                        "Arithmetic requires int operands: cannot multiply "
                        + left.typeName() + " '" + left + "' with " + right.typeName() + " '" + right + "'");
            }
            valueStack.push(new IntValue(l.v() * r.v()));
            return;
        }

        if (left.getClass() != right.getClass()) {
            throw new QueryExecutionException(ErrorCode.TYPE_MISMATCH, "Type mismatch: cannot compare "
                    + left.typeName() + " '" + left + "' with " + right.typeName() + " '" + right + "'");
        }

        if (expression instanceof EqualsTo) {
            resultStack.push(left.equals(right));
        } else if (expression instanceof NotEqualsTo) {
            resultStack.push(!left.equals(right));
        } else if (expression instanceof GreaterThan) {
            resultStack.push(left.compareTo(right) > 0);
        } else if (expression instanceof GreaterThanEquals) {
            resultStack.push(left.compareTo(right) >= 0);
        } else if (expression instanceof MinorThan) {
            resultStack.push(left.compareTo(right) < 0);
        } else { // MinorThanEquals: the only type left after the guard above
            resultStack.push(left.compareTo(right) <= 0);
        }
    }
    /**
     * Gets the schema identifier associated with this evaluator.
     * @return The schema identifier
     */
    public String getSchemaId() {
        return schemaId;
    }
}
