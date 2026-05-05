package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Optimizes query plans by removing unnecessary operators and reorganizing
 * the plan to reduce the number of tuples processed while maintaining correctness.
 *
 * This optimizer respects the key requirements:
 * 1. Proper operator implementation with getNextTuple() and reset()
 * 2. Tree-based evaluation model
 * 3. Left-deep join tree that follows the FROM clause ordering
 *
 * The optimizer applies several transformation techniques:
 * - Removing trivial operators (e.g., SELECT with always-true conditions)
 * - Pushing selections down the operator tree to filter tuples early
 * - Pushing projections down to reduce data movement
 * - Combining consecutive operators of the same type
 */
public class QueryPlanOptimizer {

    /**
     * Optimizes a query plan by applying various transformation rules.
     * Optimization rules are applied in a specific order to ensure correctness.
     * @param rootOp The root operator of the query plan to optimize
     * @return The optimized query plan
     */
    public static Operator optimize(Operator rootOp) {

        // Apply optimization rules in a specific order
        // First, push filtering operations down
        rootOp = pushSelectionsDown(rootOp);
        rootOp = pushProjectionsDown(rootOp);

        // Update schema information after push-down operations
        rootOp.updateSchema();

        // Then, combine operators where possible
        rootOp = combineConsecutiveSelects(rootOp);

        // Finally, remove unnecessary operators
        rootOp = removeUnnecessaryProjects(rootOp);
        rootOp = removeUnnecessarySelects(rootOp);
        // Verify schema consistency after all optimizations
//        verifySchemaConsistency(rootOp);
        return rootOp;
    }

    /**
     * Verifies schema consistency across the query plan.
     * Logs operator schema information for debugging purposes.
     * @param op The operator to verify
     */
    private static void verifySchemaConsistency(Operator op) {
        if (op == null) return;

        String schemaId = op.propagateSchemaId();
        System.out.println("Operator " + op.getClass().getSimpleName() +
                " has schema ID: " + schemaId);

        if (op instanceof SelectOperator) {
            SelectOperator selectOp = (SelectOperator) op;
            System.out.println("  Select condition: " + selectOp.getCondition());
        }

        if (op instanceof JoinOperator) {
            JoinOperator joinOp = (JoinOperator) op;
            System.out.println("  Join condition: " + joinOp.getJoinCondition());
            System.out.println("  Outer child schema: " + joinOp.getOuterChild().propagateSchemaId());
            verifySchemaConsistency(joinOp.getOuterChild());
        }

        if (op.hasChild()) {
            verifySchemaConsistency(op.getChild());
        }
    }

    /**
     * Removes ProjectOperators that don't actually project anything (keep all columns).
     * A projection is considered trivial if it keeps all columns from its child.
     * @param op The operator to optimize
     * @return The optimized operator
     */
    private static Operator removeUnnecessaryProjects(Operator op) {
        if (op == null) {
            return null;
        }

        // Recursive case: optimize child operators first
        if (op.hasChild()) {
            Operator optimizedChild = removeUnnecessaryProjects(op.getChild());

            // If this is a ProjectOperator, check if it's necessary
            if (op instanceof ProjectOperator) {
                ProjectOperator projectOp = (ProjectOperator) op;

                // Check if this project is trivial (doesn't eliminate any columns)
                if (isProjectTrivial(projectOp, optimizedChild)) {
                    return optimizedChild; // Skip this ProjectOperator
                }

                // Non-trivial projection, just update its child
                projectOp.setChild(optimizedChild);
                return projectOp;
            }

            // For other operator types, just update their child
            op.setChild(optimizedChild);
        }

        // Special case for JoinOperator which has two children
        if (op instanceof JoinOperator) {
            JoinOperator joinOp = (JoinOperator) op;
            Operator optimizedOuterChild = removeUnnecessaryProjects(joinOp.getOuterChild());
            joinOp.setOuterChild(optimizedOuterChild);
        }

        return op;
    }

    /**
     * Checks if a ProjectOperator doesn't actually reduce the columns.
     * A projection is trivial if it keeps all columns from its child.
     * @param projectOp The ProjectOperator to check
     * @param childOp The child operator
     * @return true if the projection is trivial, false otherwise
     */
    private static boolean isProjectTrivial(ProjectOperator projectOp, Operator childOp) {
        List<Column> projectedColumns = projectOp.getColumns();

        // If projecting zero columns, it's definitely not trivial
        if (projectedColumns.isEmpty()) {
            return false;
        }

        // If the child is a SumOperator, never consider the projection trivial
        if (childOp instanceof SumOperator) {
            return false;
        }

        // Get child schema to compare column sets
        String childSchemaId = childOp.propagateSchemaId();
        Map<String, Integer> childSchema = getOperatorSchema(childSchemaId);

        if (childSchema == null) {
            return false;
        }

        // First check: Count must match
        if (childSchema.size() != projectedColumns.size()) {
            return false;
        }

        // Second check: For each projected column, verify it exists in the
        // child schema and is at the expected position
        for (int i = 0; i < projectedColumns.size(); i++) {
            Column column = projectedColumns.get(i);
            String tableName = column.getTable().getName();
            String columnName = column.getColumnName().toLowerCase();
            String fullName = tableName + "." + columnName;

            // Check if this column exists in the schema
            Integer index = childSchema.get(columnName);
            if (index == null) {
                // Try the fully qualified name
                index = childSchema.get(fullName);
            }

            // If column not found or at different position, not trivial
            if (index == null || index != i) {
                return false;
            }
        }

        // If we get here, the projection preserves all columns in their original order
        return true;
    }

    /**
     * Gets the schema for an operator by ID, handling both base and intermediate schemas.
     * @param schemaId The schema ID to retrieve
     * @return The schema as a map from column names to indices
     */
    public static Map<String, Integer> getOperatorSchema(String schemaId) {
        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            // For intermediate schemas
            return DBCatalog.getInstance().getIntermediateSchema(schemaId);
        } else {
            // For base table schemas
            return DBCatalog.getInstance().getDBSchemata(schemaId);
        }
    }

    /**
     * Removes SelectOperators with conditions that are always true.
     * @param op The operator to optimize
     * @return The optimized operator
     */
    private static Operator removeUnnecessarySelects(Operator op) {
        if (op == null) {
            return null;
        }

        // Recursive case: optimize child operators first
        if (op.hasChild()) {
            Operator optimizedChild = removeUnnecessarySelects(op.getChild());

            // If this is a SelectOperator, check if it's necessary
            if (op instanceof SelectOperator) {
                SelectOperator selectOp = (SelectOperator) op;

                // Check if this selection is trivial (always true)
                if (isSelectionTrivial(selectOp)) {
                    return optimizedChild; // Skip this SelectOperator
                }

                // Non-trivial selection, just update its child
                selectOp.setChild(optimizedChild);
                return selectOp;
            }

            // For other operator types, just update their child
            op.setChild(optimizedChild);
        }

        // Special case for JoinOperator which has two children
        if (op instanceof JoinOperator) {
            JoinOperator joinOp = (JoinOperator) op;
            Operator optimizedOuterChild = removeUnnecessarySelects(joinOp.getOuterChild());
            joinOp.setOuterChild(optimizedOuterChild);
        }

        return op;
    }

    /**
     * Checks if a SELECT condition is trivial (always true).
     * Currently identifies simple cases like "1 = 1".
     * @param selectOp The SelectOperator to check
     * @return true if the selection is trivial, false otherwise
     */
    private static boolean isSelectionTrivial(SelectOperator selectOp) {
        Expression condition = selectOp.getCondition();

        // Check for common trivial conditions like 1 = 1
        if (condition instanceof EqualsTo) {
            EqualsTo equals = (EqualsTo) condition;
            if (equals.getLeftExpression() instanceof LongValue &&
                    equals.getRightExpression() instanceof LongValue) {
                LongValue left = (LongValue) equals.getLeftExpression();
                LongValue right = (LongValue) equals.getRightExpression();
                return left.getValue() == right.getValue();
            }
        }

        return false;
    }

    /**
     * Combines consecutive SelectOperators into a single SelectOperator.
     * This reduces the number of operators in the plan and simplifies execution.
     * @param op The operator to optimize
     * @return The optimized operator
     */
    private static Operator combineConsecutiveSelects(Operator op) {
        if (op == null) {
            return null;
        }

        // Recursive case: optimize child operators first
        if (op.hasChild()) {
            Operator optimizedChild = combineConsecutiveSelects(op.getChild());

            // If this is a SelectOperator and its child is also a SelectOperator,
            // combine them
            if (op instanceof SelectOperator && optimizedChild instanceof SelectOperator) {
                SelectOperator parentSelect = (SelectOperator) op;
                SelectOperator childSelect = (SelectOperator) optimizedChild;

                // Combine the two selection conditions
                Expression combinedCondition = new AndExpression(
                        parentSelect.getCondition(),
                        childSelect.getCondition()
                );
                // Create a new SelectOperator with the combined condition
                return new SelectOperator(childSelect.getChild(), combinedCondition);
            }

            // No combination possible, just update the child
            op.setChild(optimizedChild);
        }

        // Special case for JoinOperator which has two children
        if (op instanceof JoinOperator) {
            JoinOperator joinOp = (JoinOperator) op;
            Operator optimizedOuterChild = combineConsecutiveSelects(joinOp.getOuterChild());
            joinOp.setOuterChild(optimizedOuterChild);
        }

        return op;
    }

    /**
     * Pushes selection operations down the operator tree to reduce
     * intermediate result sizes as early as possible.
     * This respects the left-deep join tree structure.
     * @param op The operator to optimize
     * @return The optimized operator
     */
    private static Operator pushSelectionsDown(Operator op) {
        if (op == null) return null;

        // Process selections at the current level
        if (op instanceof SelectOperator) {
            SelectOperator selectOp = (SelectOperator) op;
            Expression condition = selectOp.getCondition();

            // First, try to split complex conditions
            List<Expression> splitConditions = splitAndConditions(condition);

            if (splitConditions.size() > 1) {
                return handleSplitSelectionConditions(selectOp, splitConditions);
            }

            // Try to push the single selection down
            if (selectOp.getChild() instanceof JoinOperator) {
                return pushSelectionIntoJoin(selectOp);
            }
        }

        // Recursively process children
        if (op.hasChild()) {
            op.setChild(pushSelectionsDown(op.getChild()));
        }

        // Special case for JoinOperator's outer child
        if (op instanceof JoinOperator) {
            JoinOperator joinOp = (JoinOperator) op;
            joinOp.setOuterChild(pushSelectionsDown(joinOp.getOuterChild()));
        }

        return op;
    }

    /**
     * Handles split selection conditions by pushing each one down separately.
     * @param selectOp The SelectOperator with the original condition
     * @param splitConditions The split conditions to process
     * @return The optimized operator structure
     */
    private static Operator handleSplitSelectionConditions(
            SelectOperator selectOp, List<Expression> splitConditions) {
        // Get the underlying child
        Operator baseChild = selectOp.getChild();

        // Process each condition individually
        for (Expression splitCondition : splitConditions) {

            // Create a temporary SelectOperator for this condition
            SelectOperator tempSelect = new SelectOperator(baseChild, splitCondition);

            // Try to push it down
            Operator optimizedOp = pushSelectionsDown(tempSelect);

            // If optimization changed the operator type, it was pushed down
            if (!(optimizedOp instanceof SelectOperator)) {
                baseChild = optimizedOp;
            } else {
                // Couldn't push this selection down, keep it above the result so far
                baseChild = optimizedOp;
            }
        }

        return baseChild;
    }

    /**
     * Pushes a selection into a join, potentially moving conditions to the join's children.
     * @param selectOp The SelectOperator to push down
     * @return The optimized operator
     */
    private static Operator pushSelectionIntoJoin(SelectOperator selectOp) {
        JoinOperator joinOp = (JoinOperator) selectOp.getChild();
        Expression condition = selectOp.getCondition();

        // Analyze the condition
        Set<String> tablesInCondition = extractTableNames(condition);
        String outerSchemaId = joinOp.getOuterChild().propagateSchemaId();
        String innerSchemaId = joinOp.getChild().propagateSchemaId();
        Set<String> outerTables = getTablesInSchema(outerSchemaId);
        Set<String> innerTables = getTablesInSchema(innerSchemaId);
        // Check if condition applies to only outer child
        if (outerTables.containsAll(tablesInCondition)) {
            joinOp.setOuterChild(new SelectOperator(joinOp.getOuterChild(), condition));
            return joinOp;
        }

        // Check if condition applies to only inner child
        if (innerTables.containsAll(tablesInCondition)) {
            joinOp.setChild(new SelectOperator(joinOp.getChild(), condition));
            return joinOp;
        }

        // If it's a join condition or can't be pushed down, keep it
        return selectOp;
    }

    /**
     * Splits a condition with AND operators into individual conditions.
     * For example, "A AND B AND C" becomes a list of [A, B, C].
     *
     * @param expr The expression to split
     * @return A list of individual expressions
     */
    private static List<Expression> splitAndConditions(Expression expr) {
        List<Expression> result = new ArrayList<>();

        if (expr instanceof AndExpression) {
            AndExpression and = (AndExpression) expr;
            result.addAll(splitAndConditions(and.getLeftExpression()));
            result.addAll(splitAndConditions(and.getRightExpression()));
        } else {
            result.add(expr);
        }

        return result;
    }

    /**
     * Extracts table names referenced in an expression.
     *
     * @param expr The expression to analyze
     * @return A set of table names referenced in the expression
     */
    private static Set<String> extractTableNames(Expression expr) {
        final Set<String> tables = new HashSet<>();

        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (column.getTable() != null && column.getTable().getName() != null) {
                    tables.add(column.getTable().getName());
                }
            }
        });

        return tables;
    }

    /**
     * Gets the set of table names contained in a schema.
     *
     * @param schemaId The schema ID to check
     * @return A set of table names in the schema
     */
    private static Set<String> getTablesInSchema(String schemaId) {
        Set<String> tables = new HashSet<>();

        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            // For intermediate schemas, extract table names from column keys
            Map<String, Integer> schema = DBCatalog.getInstance().getIntermediateSchema(schemaId);
            if (schema != null) {
                for (String key : schema.keySet()) {
                    int dotIndex = key.indexOf('.');
                    if (dotIndex > 0) {
                        tables.add(key.substring(0, dotIndex));
                    }
                }
            }
        } else {
            // For base tables, the schema ID is the table name
            tables.add(schemaId);
        }

        return tables;
    }

    /**
     * Analyzes a query plan and pushes projections down where possible.
     * This reduces the amount of data processed at each operator.
     *
     * @param rootOp The root operator of the plan
     * @return The optimized plan with pushed-down projections
     */
    private static Operator pushProjectionsDown(Operator rootOp) {

        // Find ProjectOperator and collect required columns from operators above it
        ProjectOperatorInfo projectInfo = findProjectOperator(rootOp, new HashSet<>());

        if (projectInfo != null) {

            if (!projectInfo.requiredColumns.isEmpty()) {
                // Push the projection down with the required columns
                Operator optimizedChild = pushProjectionDown(
                        projectInfo.projectOp.getChild(),
                        projectInfo.requiredColumns
                );

                // Update the project operator's child
                projectInfo.projectOp.setChild(optimizedChild);
            }

        }

        return rootOp;
    }

    /**
     * Helper class for tracking projection operators and their required columns.
     */
    private static class ProjectOperatorInfo {
        ProjectOperator projectOp;
        Set<Column> requiredColumns;

        ProjectOperatorInfo(ProjectOperator projectOp, Set<Column> requiredColumns) {
            this.projectOp = projectOp;
            this.requiredColumns = requiredColumns;
        }
    }

    /**
     * Searches for a ProjectOperator in the operator tree.
     * Also collects columns required by operators above the ProjectOperator.
     * @param op The operator to search
     * @param parentRequiredColumns Columns required by parent operators
     * @return Information about the found ProjectOperator, or null if none found
     */
    private static ProjectOperatorInfo findProjectOperator(
            Operator op,
            Set<Column> parentRequiredColumns) {

        if (op == null) return null;

        // If this is a ProjectOperator, we found what we're looking for
        if (op instanceof ProjectOperator) {
            ProjectOperator projectOp = (ProjectOperator) op;

            // Combine project columns with parent required columns
            // MODIFIED: Use deduplication to combine columns properly
            List<Column> allColumns = new ArrayList<>();
            allColumns.addAll(projectOp.getColumns());
            allColumns.addAll(parentRequiredColumns);

            Set<Column> combinedColumns = ColumnIdentity.deduplicateColumns(allColumns);

            return new ProjectOperatorInfo(projectOp, combinedColumns);
        }

        // Early termination: Stop if we encounter operators that would not have ProjectOperator below them
        if (op instanceof JoinOperator || op instanceof ScanOperator || op instanceof SelectOperator) {
            return null;
        }

        // Current operator's required columns - preserve existing columns while adding new ones
        Set<Column> currentRequiredColumns = new HashSet<>(parentRequiredColumns);

        // Add columns required by this operator
        if (op instanceof SortOperator) {
            SortOperator sortOp = (SortOperator) op;

            // MODIFIED: Correctly add sort columns with deduplication
            List<Column> allColumns = new ArrayList<>(currentRequiredColumns);
            List<Column> sortColumns = sortOp.getSortColumns();
            if (!sortColumns.isEmpty()) { // dont add empty ones, just in case
                allColumns.addAll(sortColumns);
                currentRequiredColumns = ColumnIdentity.deduplicateColumns(allColumns);
            }
        }
        // For DuplicateEliminationOperator, no extra columns are needed

        // Recursively search in child (which should only be necessary for Sort/DuplicateElimination)
        if (op.hasChild()) {
            return findProjectOperator(op.getChild(), currentRequiredColumns);
        }

        // No ProjectOperator found in this subtree
        return null;
    }

    /**
     * Recursively pushes projections down the operator tree.
     * Ensures that all columns needed by operators are preserved.
     * @param op The operator to process
     * @param requiredColumns The set of columns required at this level
     * @return The optimized operator tree
     */
    private static Operator pushProjectionDown(Operator op, Set<Column> requiredColumns) {
        if (op == null) {
            return null;
        }

        // Handle different operator types
        if (op instanceof SelectOperator) {
            return pushProjectionThroughSelect(op, requiredColumns);
        }
        else if (op instanceof JoinOperator) {
            return pushProjectionThroughJoin(op, requiredColumns);
        }
        else if (op instanceof ScanOperator) {
            return pushProjectionThroughScan(op, requiredColumns);
        }
        else if (op instanceof DuplicateEliminationOperator ||
                op instanceof SortOperator ||
                op instanceof SumOperator) {
            return pushProjectionThroughPassthroughOp(op, requiredColumns);
        }

        // Default case: if operator has a child, recursively optimize it
        if (op.hasChild()) {
            Operator optimizedChild = pushProjectionDown(op.getChild(), requiredColumns);
            op.setChild(optimizedChild);
        }

        return op;
    }

    /**
     * Pushes projection through a SelectOperator.
     * Ensures all columns needed for the selection condition are preserved.
     * @param op The SelectOperator
     * @param requiredColumns Columns required by parent operators
     * @return The optimized operator tree
     */
    private static Operator pushProjectionThroughSelect(Operator op, Set<Column> requiredColumns) {
        SelectOperator selectOp = (SelectOperator) op;
        Expression condition = selectOp.getCondition();

        // Add columns used in the condition to required columns
        Set<Column> enhancedRequiredColumns = new HashSet<>(requiredColumns);
        addConditionColumns(condition, enhancedRequiredColumns);

        // Recursively push projection down
        Operator optimizedChild = pushProjectionDown(selectOp.getChild(), enhancedRequiredColumns);
        selectOp.setChild(optimizedChild);

        return selectOp;
    }

    /**
     * Adds columns referenced in a condition to a set of required columns.
     * @param condition The condition to analyze
     * @param requiredColumns The set to add columns to
     */
    private static void addConditionColumns(Expression condition, Set<Column> requiredColumns) {
        ColumnExtractor extractor = new ColumnExtractor();
        condition.accept(extractor);
        requiredColumns.addAll(extractor.getColumns());
    }

    /**
     * Pushes projection through a JoinOperator.
     * Splits required columns between the join's children and preserves join condition columns.
     * @param op The JoinOperator
     * @param requiredColumns Columns required by parent operators
     * @return The optimized operator tree
     */
    private static Operator pushProjectionThroughJoin(Operator op, Set<Column> requiredColumns) {
        JoinOperator joinOp = (JoinOperator) op;

        // First add columns used in join condition
        Set<Column> enhancedRequiredColumns = new HashSet<>(requiredColumns);
        Expression joinCondition = joinOp.getJoinCondition();
        if (joinCondition != null) {
            addConditionColumns(joinCondition, enhancedRequiredColumns);
        }

        // Split columns by table
        Set<Column> leftColumns = new HashSet<>();
        Set<Column> rightColumns = new HashSet<>();
        splitColumnsByTable(
                enhancedRequiredColumns,
                joinOp.getOuterChild().propagateSchemaId(),
                joinOp.getChild().propagateSchemaId(),
                leftColumns,
                rightColumns
        );

        // Recursively push down to both children
        Operator optimizedOuterChild;
        if (!leftColumns.isEmpty()) {
            optimizedOuterChild = pushProjectionDown(joinOp.getOuterChild(), leftColumns);
        } else {
            optimizedOuterChild = joinOp.getOuterChild();
        }

        Operator optimizedInnerChild;
        if (!rightColumns.isEmpty()) {
            optimizedInnerChild = pushProjectionDown(joinOp.getChild(), rightColumns);
        } else {
            optimizedInnerChild = joinOp.getChild();
        }

        joinOp.setOuterChild(optimizedOuterChild);
        joinOp.setChild(optimizedInnerChild);

        return joinOp;
    }

    /**
     * Splits a set of columns between left and right sides of a join.
     * @param allColumns The set of all required columns
     * @param outerSchemaId Schema ID for the left side
     * @param innerSchemaId Schema ID for the right side
     * @param leftColumns Output set for left side columns
     * @param rightColumns Output set for right side columns
     */
    private static void splitColumnsByTable(
            Set<Column> allColumns,
            String outerSchemaId,
            String innerSchemaId,
            Set<Column> leftColumns,
            Set<Column> rightColumns) {

        Set<String> outerTables = getTablesInSchema(outerSchemaId);
        Set<String> innerTables = getTablesInSchema(innerSchemaId);

        for (Column col : allColumns) {
            String tableName = col.getTable().getName();

            if (outerTables.contains(tableName)) {
                leftColumns.add(col);
            } else if (innerTables.contains(tableName)) {
                rightColumns.add(col);
            } else {
                // If we can't determine, add to both to be safe
                leftColumns.add(col);
                rightColumns.add(col);
            }
        }
    }

    /**
     * Pushes projection through a ScanOperator.
     * Creates a ProjectOperator if not all columns are required.
     * @param op The ScanOperator
     * @param requiredColumns Columns required by parent operators
     * @return The optimized operator tree
     */
    private static Operator pushProjectionThroughScan(Operator op, Set<Column> requiredColumns) {
        ScanOperator scanOp = (ScanOperator) op;
        String tableName = scanOp.getTableName();

        // Filter columns for this table only
        List<Column> tableColumns = new ArrayList<>();
        for (Column col : requiredColumns) {
            if (col.getTable().getName().equals(tableName)) {
                tableColumns.add(col);
            }
        }

        // Check if the projection would be empty
        if (tableColumns.isEmpty()) {
            // Don't create an empty projection, just return the scan operator
            return scanOp;
        }

        // If we're not selecting all columns, add a projection
        Map<String, Integer> tableSchema = DBCatalog.getInstance().getDBSchemata(tableName);
        if (tableColumns.size() < tableSchema.size()) {
//                    " with columns: " + tableColumns);
            return new ProjectOperator(scanOp, tableColumns);
        }

        return scanOp;
    }

    /**
     * Pushes projection through passthrough operators like DuplicateElimination, Sort, and Sum.
     * These operators typically need all colum
     * ns from their input for proper operation.
     * @param op The operator to process
     * @param requiredColumns Columns required by parent operators
     * @return The optimized operator tree
     */
    private static Operator pushProjectionThroughPassthroughOp(Operator op, Set<Column> requiredColumns) {
        if (requiredColumns.isEmpty()) {
            return op; // Don't push empty projections
        }

        Operator optimizedChild = pushProjectionDown(op.getChild(), requiredColumns);
        op.setChild(optimizedChild);
        return op;
    }
}

