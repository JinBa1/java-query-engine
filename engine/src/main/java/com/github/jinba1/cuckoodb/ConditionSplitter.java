package com.github.jinba1.cuckoodb;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.util.*;

/**
 * The ConditionSplitter class analyzes and splits join conditions into three categories:
 * outer conditions, inner conditions, and join conditions.
 * This class is essential for query optimization, particularly for selection push-down,
 * where conditions that apply only to one side of a join can be evaluated earlier.
 * By splitting the conditions appropriately, the query execution can filter tuples
 * as early as possible, reducing the number of tuples processed in join operations.
 * The class follows the visitor pattern to analyze expressions and determine which
 * tables they reference. Based on this analysis, conditions are categorized as:
 * - Outer conditions: Apply only to the outer (left) side of the join
 * - Inner conditions: Apply only to the inner (right) side of the join
 * - Join conditions: Apply to both sides and must be evaluated during the join
 */
public class ConditionSplitter extends ExpressionVisitorAdapter {
    private final PlanContext ctx;
    private final String outerSchemaId;
    private final String innerSchemaId;

    private final List<Expression> outerConditions = new ArrayList<>();
    private final List<Expression> innerConditions = new ArrayList<>();
    private final List<Expression> joinConditions = new ArrayList<>();

    /**
     * Constructs a ConditionSplitter with specified schema identifiers.
     * @param ctx The per-query context for schema resolution
     * @param outerSchemaId The schema identifier for the outer (left) side of the join
     * @param innerSchemaId The schema identifier for the inner (right) side of the join
     */
    public ConditionSplitter(PlanContext ctx, String outerSchemaId, String innerSchemaId) {
        this.ctx = ctx;
        this.outerSchemaId = outerSchemaId;
        this.innerSchemaId = innerSchemaId;
    }
    /**
     * Visits an AND expression and processes each of its operands separately.
     * This allows proper splitting of compound conditions.
     * @param andExpression The AND expression to process
     */
    @Override
    public void visit(AndExpression andExpression) {
        // Process left and right separately
        andExpression.getLeftExpression().accept(this);
        andExpression.getRightExpression().accept(this);
    }

    /**
     * Visits an EqualsTo expression and determines its category.
     * @param equalsTo The equals comparison to process
     */
    @Override
    public void visit(EqualsTo equalsTo) {
        processComparison(equalsTo);
    }

    /**
     * Visits a NotEqualsTo expression and determines its category.
     * @param notEqualsTo The not-equals comparison to process
     */
    @Override
    public void visit(NotEqualsTo notEqualsTo) {
        processComparison(notEqualsTo);
    }

    /**
     * Visits a MinorThan expression and determines its category.
     * @param minorThan The not-equals comparison to process
     */
    @Override
    public void visit(MinorThan minorThan) {
        processComparison(minorThan);
    }

    /**
     * Visits a MinorThanEquals expression and determines its category.
     * @param minorThanEquals The not-equals comparison to process
     */
    @Override
    public void visit(MinorThanEquals minorThanEquals) {
        processComparison(minorThanEquals);
    }

    /**
     * Visits a GreaterThan expression and determines its category.
     * @param greaterThan The not-equals comparison to process
     */
    @Override
    public void visit(GreaterThan greaterThan) {
        processComparison(greaterThan);
    }

    /**
     * Visits a GreaterThanEquals expression and determines its category.
     * @param greaterThanEquals The not-equals comparison to process
     */
    @Override
    public void visit(GreaterThanEquals greaterThanEquals) {
        processComparison(greaterThanEquals);
    }
    /**
     * Processes a binary comparison expression to determine its category.
     * Analyzes which tables are referenced in the expression and assigns
     * the expression to the appropriate category.
     * @param expr The binary expression to process
     */
    private void processComparison(BinaryExpression expr) {
        // Extract tables referenced in left and right
        Set<String> leftTables = getReferencedTables(expr.getLeftExpression());
        Set<String> rightTables = getReferencedTables(expr.getRightExpression());

        // Determine if this is an outer, inner, or join condition
        boolean usesOuterOnly = referencesSchemaOnly(leftTables, rightTables, outerSchemaId);
        boolean usesInnerOnly = referencesSchemaOnly(leftTables, rightTables, innerSchemaId);

        if (usesOuterOnly) {
            outerConditions.add(expr);
        } else if (usesInnerOnly) {
            innerConditions.add(expr);
        } else {
            joinConditions.add(expr);
        }
    }

    /**
     * Extracts table names referenced in an expression.
     * @param expr The expression to analyze
     * @return A set of table names referenced in the expression
     */
    private Set<String> getReferencedTables(Expression expr) {
        // Extract table names from an expression
        final Set<String> tables = new HashSet<>();

        if (expr instanceof Column) {
            Column col = (Column) expr;
            if (col.getTable() != null) {
                tables.add(col.getTable().getName());
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            tables.addAll(getReferencedTables(binExpr.getLeftExpression()));
            tables.addAll(getReferencedTables(binExpr.getRightExpression()));
        }

        return tables;
    }

    /**
     * Checks if all referenced tables belong to a specific schema.
     * @param leftTables The tables referenced on the left side of the expression
     * @param rightTables The tables referenced on the right side of the expression
     * @param schemaId The schema identifier to check against
     * @return true if all tables are from the specified schema, false otherwise
     */
    private boolean referencesSchemaOnly(Set<String> leftTables, Set<String> rightTables, String schemaId) {
        // Check if all tables are from a specific schema
        Set<String> allTables = new HashSet<>(leftTables);
        allTables.addAll(rightTables);

        // Use the schema tracking system to check
        for (String table : allTables) {
            // need to integrate with schema tracking to check if this table belongs to our schema
            if (!tableInSchema(table, schemaId)) {
                return false;
            }
        }

        return !allTables.isEmpty();
    }

    /**
     * Checks if a table belongs to a specific schema.
     * @param tableName The name of the table to check
     * @param schemaId The schema identifier to check against
     * @return true if the table is in the specified schema, false otherwise
     */
    private boolean tableInSchema(String tableName, String schemaId) {
        // Use the DBCatalog to check if table is in schema
        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            Map<String, Integer> schema = ctx.getIntermediateSchema(schemaId);
            if (schema == null) return false;

            // Check if any column key has this table prefix
            for (String key : schema.keySet()) {
                if (key.startsWith(tableName + ".")) {
                    return true;
                }
            }
            return false;
        } else {
            // For base tables, check if the table exists
            return DBCatalog.getInstance().tableExists(tableName);
        }
    }

    /**
     * Combines a list of expressions with AND operators.
     * @param conditions The list of expressions to combine
     * @return A single expression combining all conditions, or null if the list is empty
     */
    private Expression combineConditions(List<Expression> conditions) {
        if (conditions.isEmpty()) return null;

        Expression result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = new AndExpression(result, conditions.get(i));
        }

        return result;
    }

    /**
     * Returns the combined condition that applies only to the outer side of the join.
     * @return A single expression combining all outer conditions, or null if none exist
     */
    public Expression getOuterCondition() {
        return combineConditions(outerConditions);
    }
    /**
     * Returns the combined condition that applies only to the inner side of the join.
     * @return A single expression combining all inner conditions, or null if none exist
     */
    public Expression getInnerCondition() {
        return combineConditions(innerConditions);
    }
    /**
     * Returns the combined condition that applies to both sides of the join.
     * @return A single expression combining all join conditions, or null if none exist
     */
    public Expression getJoinCondition() {
        return combineConditions(joinConditions);
    }
}