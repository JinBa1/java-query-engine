package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.*;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.io.FileReader;
import java.util.*;

/**
 * The QueryPlanner class translates SQL queries into executable query plans.
 * It parses SQL statements, constructs appropriate operator trees, and applies optimizations.
 * This class is responsible for:
 * 1. Parsing SQL statements from input files
 * 2. Building operator trees with scan, selection, projection, join, and aggregation operators
 * 3. Ensuring proper schema propagation throughout the operator tree
 * 4. Applying query optimizations when enabled
 * The planner follows a bottom-up approach, starting with scan operators for base tables,
 * then adding join, selection, aggregation, and projection operators as needed.
 * It constructs left-deep join trees in accordance with the order of tables in the FROM clause.
 * @see Operator The abstract base class for all query operators
 * @see DBCatalog The catalog with schema information for column resolution
 * @see QueryPlanOptimizer The optimizer for improving constructed query plans
 */
public class QueryPlanner {

    /**
     * Parses an SQL statement from a file and constructs a query plan.
     * This is the main entry point for query processing in BlazeDB.
     * @param filename The path to a file containing a valid SQL query
     * @return The root operator of the constructed query plan, or null if parsing fails
     */
    public static Operator parseStatement(String filename) {
        Operator rootOp = null;
        try {
            Statement statement = CCJSqlParserUtil.parse(new FileReader(filename));

            if (statement != null) {
                Select select = (Select) statement;
                // Create scan operator for the first table
                rootOp = createScanOperator(select);

                // Process joins if they exist
                if (existJoinOp(select)) {
                    rootOp = processJoins(rootOp, select);
                } else if (existSelectOp(select)) {
                    // For queries without joins, add selection directly
                    rootOp = new SelectOperator(rootOp, select.getPlainSelect().getWhere());
                }

                // Process GROUP BY and aggregation
                rootOp = processGroupByAndAggregation(rootOp, select);

                // Process projection (if needed)
                rootOp = processProjection(rootOp, select);

                // Process DISTINCT and ORDER BY
                rootOp = processDistinctAndOrderBy(rootOp, select);
            }
        } catch (Exception e) {
            System.err.println("Exception occurred during parsing");
            e.printStackTrace();
        }

        // Ensure schemas are properly registered
        ensureAllSchemasRegistered(rootOp);

        // Apply query optimization if enabled
        if (Constants.useQueryOptimization) {
            rootOp = QueryPlanOptimizer.optimize(rootOp);
        }

        return rootOp;
    }

    /**
     * Creates a scan operator for the first table in the FROM clause.
     * @param select The SQL SELECT statement
     * @return A ScanOperator for the first table
     */
    private static Operator createScanOperator(Select select) {
        Table firstTable = (Table) select.getPlainSelect().getFromItem();
        return new ScanOperator(firstTable.getName());
    }

    /**
     * Processes JOIN operations for a query.
     * This method constructs a left-deep join tree following the order of tables
     * in the FROM clause. It extracts join conditions from the WHERE clause
     * and applies them appropriately to the JOIN operators.
     * @param rootOp The initial operator (scan of the first table)
     * @param select The SELECT statement being processed
     * @return The root operator of the join tree
     */
    private static Operator processJoins(Operator rootOp, Select select) {
        ExpressionPreprocessor preprocessor = new ExpressionPreprocessor();

        Expression whereExpression = select.getPlainSelect().getWhere();

        List<Expression> joinExpressions;
        List<Expression> selectExpressions;

        if (whereExpression != null) {
            preprocessor.evaluate(select.getPlainSelect().getWhere());
            joinExpressions = preprocessor.getJoinExpressions();
            selectExpressions = preprocessor.getSelectExpressions();
        } else {
            // these lists need to be constructed but preprocessor cant take null expression
            // therefore assign empty lists.
            joinExpressions = new ArrayList<>();
            selectExpressions = new ArrayList<>();
        }

        List<Table> tables = getTablesInOrder(select);
        Set<String> joinedTableNames = new HashSet<>();
        joinedTableNames.add(((Table) select.getPlainSelect().getFromItem()).getName()); // the first table in the from clause

        // Build joins in the order specified in the FROM clause
        for (Table table : tables) {
            Expression joinCondition = findJoinCondition(joinExpressions, joinedTableNames, table);

            Operator rightOp = new ScanOperator(table.getName());
            rootOp = new JoinOperator(rootOp, rightOp, joinCondition);

            joinedTableNames.add(table.getName());

        }

        // Add any remaining selection conditions after joins
        if (!selectExpressions.isEmpty()) {
            rootOp = new SelectOperator(rootOp, combineExpression(selectExpressions));
        }

        return rootOp;
    }

    /**
     * Processes GROUP BY and aggregation operations.
     * Handles both queries with explicit GROUP BY and those with SUM aggregates only.
     * @param rootOp The operator tree so far
     * @param select The SELECT statement being processed
     * @return Updated operator tree with aggregation
     */
    private static Operator processGroupByAndAggregation(Operator rootOp, Select select) {
        // Handle GROUP BY with SUM
        if (existGroupByOp(select)) {
            List<Column> groupByColumns = extractGroupByColumns(select);
            List<AggregateCall> aggregateCalls = extractAggregateCalls(select);
            List<Column> outputColumns = extractNonAggregateColumns(select);

            Set<Column> requiredColumns = getRequiredColumnsForAggregation(groupByColumns, aggregateCalls, select);

            // Project only required columns before aggregation
            Operator childOp = rootOp;
            rootOp = new ProjectOperator(childOp, new ArrayList<>(requiredColumns));

            // Add the aggregate operator
            rootOp = new AggregateOperator(rootOp, groupByColumns, aggregateCalls, outputColumns);
        }
        // Handle SUM without GROUP BY
        else if (existSumAggregate(select)) {
            List<Column> groupByColumns = new ArrayList<>(); // Empty for no grouping
            List<AggregateCall> aggregateCalls = extractAggregateCalls(select);
            List<Column> outputColumns = new ArrayList<>(); // Empty for no grouping

            Set<Column> requiredColumns = getRequiredColumnsForAggregation(groupByColumns, aggregateCalls, select);
            Operator childOp = rootOp;
            rootOp = new ProjectOperator(childOp, new ArrayList<>(requiredColumns));

            rootOp = new AggregateOperator(rootOp, groupByColumns, aggregateCalls, outputColumns);
        }

        return rootOp;
    }

    /**
     * Processes projection operations.
     * Adds a ProjectOperator if the query selects specific columns.
     * @param rootOp The operator tree so far
     * @param select The SELECT statement being processed
     * @return Updated operator tree with projection
     */
    private static Operator processProjection(Operator rootOp, Select select) {
        // Add projection if needed (only if not GROUP BY or SUM or if SELECT *)
        if (existProjectOp(select) && !existSumAggregate(select) && !existGroupByOp(select)) {
            rootOp = new ProjectOperator(rootOp, getProjectCols(select));
        }

        return rootOp;
    }

    /**
     * Processes DISTINCT and ORDER BY operations.
     * Adds DuplicateEliminationOperator and/or SortOperator as needed.
     * @param rootOp The operator tree so far
     * @param select The SELECT statement being processed
     * @return Updated operator tree with DISTINCT and/or ORDER BY
     */
    private static Operator processDistinctAndOrderBy(Operator rootOp, Select select) {
        // Add DISTINCT if needed
        if (existDistinctOp(select)) {
            rootOp = new DuplicateEliminationOperator(rootOp);
        }

        // Add ORDER BY if needed
        if (existSortOp(select)) {
            rootOp = new SortOperator(rootOp, getSortCols(select));
        }

        return rootOp;
    }

    /**
     * Checks if a GROUP BY clause exists in the query.
     * @param select The SELECT statement to check
     * @return true if a GROUP BY clause exists, false otherwise
     */
    private static boolean existGroupByOp(Select select) {
        return select.getPlainSelect().getGroupBy() != null &&
                select.getPlainSelect().getGroupBy().getGroupByExpressionList() != null &&
                !select.getPlainSelect().getGroupBy().getGroupByExpressionList().isEmpty();
    }

    /**
     * Checks if any SUM aggregates exist in the query.
     * @param select The SELECT statement to check
     * @return true if any SUM aggregates exist, false otherwise
     */
    private static boolean existSumAggregate(Select select) {
        List<SelectItem<?>> selectItems = select.getPlainSelect().getSelectItems();
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof Function) {
                Function function = (Function) expr;
                if (Constants.SUM_FUNCTION_NAME.equalsIgnoreCase(function.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extracts GROUP BY columns from a query.
     * @param select The SELECT statement to extract from
     * @return A list of Column objects representing the GROUP BY columns
     */
    private static List<Column> extractGroupByColumns(Select select) {
        List<Column> groupByColumns = new ArrayList<>();
        List<?> groupByExpressions = select.getPlainSelect().getGroupBy().getGroupByExpressionList();
        for (Object obj : groupByExpressions) {
            Expression expr = (Expression) obj;
            if (expr instanceof Column) {
                Column column = (Column) expr;
                groupByColumns.add(column);
//                        column.getTable().getName() + "." + column.getColumnName());
            } else {
                throw new UnsupportedOperationException("Only column references are supported in GROUP BY");
            }
        }

        return groupByColumns;
    }

    /**
     * Extracts aggregate calls (SUM/COUNT/AVG/MIN/MAX) from the SELECT list, in order.
     * Computes each call's schema key in the engine's established format:
     * "SUM(student.b)" for column arguments, "SUM(0)" (call index) for expression
     * arguments, "COUNT(*)" for the star form. Keys are lowercased only at output.
     * @param select The SELECT statement to extract from
     * @return The aggregate calls in SELECT-list order
     */
    private static List<AggregateCall> extractAggregateCalls(Select select) {
        List<AggregateCall> calls = new ArrayList<>();
        for (SelectItem<?> item : select.getPlainSelect().getSelectItems()) {
            Expression expr = item.getExpression();
            if (!(expr instanceof Function function)) {
                continue;
            }
            AggregateFunction aggregateFunction = AggregateFunction.fromFunctionName(function.getName());
            if (aggregateFunction == null) {
                continue;
            }

            Expression argument;
            if (function.isAllColumns() || function.getParameters() == null
                    || function.getParameters().isEmpty()) {
                if (aggregateFunction != AggregateFunction.COUNT) {
                    throw new QueryExecutionException(
                            function.getName() + "(*) is not supported; only COUNT(*) may use '*'");
                }
                argument = null;
            } else {
                argument = (Expression) function.getParameters().get(0);
            }

            String schemaKey;
            if (argument == null) {
                schemaKey = function.getName() + "(*)";
            } else if (argument instanceof Column column) {
                schemaKey = function.getName() + "(" + column.getTable().getName() + "."
                        + column.getColumnName().toLowerCase() + ")";
            } else {
                schemaKey = function.getName() + "(" + calls.size() + ")";
            }

            calls.add(new AggregateCall(aggregateFunction, argument, schemaKey));
        }
        return calls;
    }

    /**
     * Extracts non-aggregate columns from a SELECT list.
     * @param select The SELECT statement to extract from
     * @return A list of Column objects representing non-aggregate columns
     */
    private static List<Column> extractNonAggregateColumns(Select select) {
        List<Column> nonAggregateColumns = new ArrayList<>();
        List<SelectItem<?>> selectItems = select.getPlainSelect().getSelectItems();

        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof Column) {
                nonAggregateColumns.add((Column) expr);
            }
        }

        return nonAggregateColumns;
    }

    /**
     * Checks if a DISTINCT clause exists in the query.
     * @param select The SELECT statement to check
     * @return true if a DISTINCT clause exists, false otherwise
     */
    private static boolean existDistinctOp(Select select) {
        return select.getPlainSelect().getDistinct() != null;
    }

    /**
     * Checks if an ORDER BY clause exists in the query.
     * @param select The SELECT statement to check
     * @return true if an ORDER BY clause exists, false otherwise
     */
    private static boolean existSortOp(Select select) {
        return (select.getPlainSelect().getOrderByElements() != null
                && !select.getPlainSelect().getOrderByElements().isEmpty());
    }

    /**
     * Checks if a JOIN operation is needed in the query.
     * @param select The SELECT statement to check
     * @return true if a JOIN operation is needed, false otherwise
     */
    private static boolean existJoinOp(Select select) {
        return (select.getPlainSelect().getJoins() != null && !select.getPlainSelect().getJoins().isEmpty());
    }

    /**
     * Checks if a projection operation is needed in the query.
     * @param select The SELECT statement to check
     * @return true if a projection is needed, false otherwise
     */
    private static boolean existProjectOp(Select select) {
        List<?> selectItems = select.getPlainSelect().getSelectItems();
        boolean exist = true;
        for (Object item : selectItems) {
            Expression exp = ((SelectItem<?>) item).getExpression();
            if (exp instanceof AllColumns) {
                exist = false;
                break;
            }
        }
        return exist;
    }

    /**
     * Checks if a selection operation is needed in the query.
     * @param select The SELECT statement to check
     * @return true if a selection is needed, false otherwise
     */
    private static boolean existSelectOp(Select select) {
        return (select.getPlainSelect().getWhere() != null);
    }

    /**
     * Extracts ORDER BY columns from a query.
     * @param select The SELECT statement to extract from
     * @return A list of Column objects for sorting
     */
    private static List<Column> getSortCols(Select select) {
        List<OrderByElement> orderByElements = select.getPlainSelect().getOrderByElements();
        List<Column> sortCols = new ArrayList<>();

        for (OrderByElement orderByElement : orderByElements) {
            Expression exp = orderByElement.getExpression();
            if (exp instanceof Column) {
                Column column = (Column) exp;
                sortCols.add(column);
            } else {
                throw new RuntimeException("Unexpected item: " + orderByElement + " of type " + orderByElements.getClass());
            }
        }
        return sortCols;
    }

    /**
     * Extracts projection columns from a query.
     * @param select The SELECT statement to extract from
     * @return A list of Column objects for projection
     */
    private static List<Column> getProjectCols(Select select) {
        List<?> selectItems = select.getPlainSelect().getSelectItems();
        List<Column> projectCols = new ArrayList<>();

        for (Object item : selectItems) {
            Expression exp = ((SelectItem<?>) item).getExpression();
            if (exp instanceof Column) {
                Column column = (Column) exp;
                projectCols.add(column);
            } else {
                throw new RuntimeException("Unexpected item: " + item + " of type " + item.getClass());
            }
        }
        return projectCols;
    }

    /**
     * Finds appropriate join conditions for a table being joined.
     * @param joinExpressions List of all possible join expressions
     * @param joinedTableNames Set of tables already in the join tree
     * @param rightTable The table being joined
     * @return An expression representing the join condition, or null for cross product
     */
    private static Expression findJoinCondition(List<Expression> joinExpressions, Set<String> joinedTableNames, Table rightTable) {
        if (joinExpressions == null || joinExpressions.isEmpty()) {
            return null;
        }

        List<Expression> relevantExpressions = new ArrayList<>();

        for (Expression exp : joinExpressions) {
            Set<String> tableNames = extractTableNames(exp);

            if (tableNames.contains(rightTable.getName())) {
                for (String tableName : tableNames) {
                    if (!tableName.equals(rightTable.getName()) && joinedTableNames.contains(tableName)) {
                        relevantExpressions.add(exp);
                        break;
                    }
                }
            }
        }

        return combineExpression(relevantExpressions);
    }

    /**
     * Gets tables in the order they appear in the FROM clause.
     * @param select The SELECT statement to extract from
     * @return A list of Table objects in FROM clause order
     */
    private static List<Table> getTablesInOrder(Select select) {
        List<Table> tables = new ArrayList<>();
        for (Join join : select.getPlainSelect().getJoins()) {
            if (!(join.getRightItem() instanceof Table)) {
                throw new UnsupportedOperationException("All joined items must be tables");
            }
            Table joinTable = (Table) join.getRightItem();
            tables.add(joinTable);
        }
        return tables;
    }

    /**
     * Extracts table names referenced in an expression.
     * @param expression The expression to analyze
     * @return A set of table names referenced in the expression
     */
    private static Set<String> extractTableNames(Expression expression) {
        final Set<String> tableNames = new HashSet<>();

        if (expression instanceof BinaryExpression) {
            Expression left = ((BinaryExpression) expression).getLeftExpression();
            Expression right = ((BinaryExpression) expression).getRightExpression();

            tableNames.addAll(extractTableNames(left));
            tableNames.addAll(extractTableNames(right));
        } else if (expression instanceof Column) {
            Column column = (Column) expression;
            if (column.getTable() != null && column.getTable().getName() != null) {
                tableNames.add(column.getTable().getName());
            }
        }

        return tableNames;
    }

    /**
     * Combines multiple expressions into a single AND expression.
     * @param expressions List of expressions to combine
     * @return A single combined expression, or null if the list is empty
     */
    private static Expression combineExpression(List<Expression> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return null;
        }

        Expression result = expressions.get(0);

        for (int i = 1; i < expressions.size(); i++) {
            result = new AndExpression(result, expressions.get(i));
        }

        return result;
    }

    /**
     * Ensures all schemas are properly registered throughout the operator tree.
     * @param op The root operator to check
     */
    private static void ensureAllSchemasRegistered(Operator op) {
        if (op == null) return;

        // Force schema registration
        op.ensureSchemaRegistered();

        // Process children
        if (op.hasChild()) {
            ensureAllSchemasRegistered(op.getChild());
        }

        // Special case for JoinOperator
        if (op instanceof JoinOperator) {
            ensureAllSchemasRegistered(((JoinOperator) op).getOuterChild());
        }
    }

    /**
     * Determines which columns must survive into the aggregation input:
     * group-by columns, columns inside aggregate arguments (COUNT(*) contributes none),
     * and columns from the WHERE clause.
     */
    private static Set<Column> getRequiredColumnsForAggregation(
            List<Column> groupByColumns,
            List<AggregateCall> aggregateCalls,
            Select select) {

        Set<Column> requiredColumns = new HashSet<>(groupByColumns);

        for (AggregateCall call : aggregateCalls) {
            if (call.argument() == null) {
                continue; // COUNT(*) needs no input columns
            }
            ColumnExtractor extractor = new ColumnExtractor();
            call.argument().accept(extractor);
            requiredColumns.addAll(extractor.getColumns());
        }

        Expression whereExpr = select.getPlainSelect().getWhere();
        if (whereExpr != null) {
            ColumnExtractor extractor = new ColumnExtractor();
            whereExpr.accept(extractor);
            requiredColumns.addAll(extractor.getColumns());
        }

        return requiredColumns;
    }
}
