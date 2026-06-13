package com.github.jinba1.cuckoodb;

import com.github.jinba1.cuckoodb.operator.*;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.io.FileReader;
import java.io.IOException;
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
     * Kept for existing callers; EXPLAIN-aware callers should use {@link #planQuery}.
     * @param filename The path to a file containing a valid SQL query
     * @return The root operator of the constructed query plan
     * @throws QueryExecutionException if the file is unreadable or the query cannot be planned
     */
    public static Operator parseStatement(String filename) {
        return planQuery(filename).root();
    }

    /**
     * Parses an SQL statement with explicit configuration and constructs a query plan.
     * @param filename The path to a file containing a valid SQL query
     * @param config The per-query planner configuration
     * @return The root operator of the constructed query plan
     * @throws QueryExecutionException if the file is unreadable or the query cannot be planned
     */
    public static Operator parseStatement(String filename, QueryConfig config) {
        return planQuery(filename, config).root();
    }

    /**
     * Plans one query file under the production default configuration.
     * @param filename The path to a file containing a valid SQL query (optionally EXPLAIN-prefixed)
     * @return The planned query; the root is never null
     * @throws QueryExecutionException if the file is unreadable or the query cannot be planned
     */
    public static PlannedQuery planQuery(String filename) {
        return planQuery(filename, QueryConfig.defaults());
    }

    /**
     * Plans one query file. For EXPLAIN queries, renders the operator tree before and
     * after optimization into {@link PlannedQuery#explainText()}; the returned root is
     * the optimized, executable tree either way.
     * @param filename The path to a file containing a valid SQL query (optionally EXPLAIN-prefixed)
     * @param config The per-query planner configuration
     * @return The planned query; the root is never null
     * @throws QueryExecutionException if the file is unreadable or the query cannot be planned,
     *         with an {@link ErrorCode} and a message the caller can act on
     */
    public static PlannedQuery planQuery(String filename, QueryConfig config) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(new FileReader(filename));
        } catch (JSQLParserException e) {
            throw new QueryExecutionException(ErrorCode.PARSE_ERROR,
                    "SQL syntax error: " + parserMessage(e));
        } catch (IOException e) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Could not read query file '" + filename + "': " + e.getMessage());
        }
        return planFrom(statement, config);
    }

    /**
     * Plans one query from SQL held in memory — the library/REST entry point. Parses the
     * text, then runs the identical statement-level pipeline as the file overload, so a
     * query planned from a string and the same query planned from a file are byte-for-byte
     * the same plan (and EXPLAIN renders identically).
     *
     * <p>Named {@code planSql} rather than overloading {@code planQuery(String, QueryConfig)}
     * because that signature already exists for the file path; both take a {@code String}, so
     * a distinct name is the only unambiguous way to mean "this argument is SQL text, not a
     * path". Read-only-by-construction holds across the parser: a non-SELECT statement is
     * rejected as {@link ErrorCode#UNSUPPORTED_SQL} and multi-statement input fails to parse
     * ({@link ErrorCode#PARSE_ERROR}). Unlike the file overload there is no I/O, so no
     * {@code DATA_ERROR} branch.
     * @param sql    the SQL query text (optionally EXPLAIN-prefixed); not a file path
     * @param config the per-query planner configuration
     * @return the planned query; the root is never null
     * @throws QueryExecutionException with an {@link ErrorCode} the caller can act on
     */
    public static PlannedQuery planSql(String sql, QueryConfig config) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new QueryExecutionException(ErrorCode.PARSE_ERROR,
                    "SQL syntax error: " + parserMessage(e));
        }
        return planFrom(statement, config);
    }

    /**
     * The shared planning core: takes an already-parsed statement and produces the optimized,
     * executable plan (plus EXPLAIN text when the statement is EXPLAIN-prefixed). Both source
     * overloads delegate here so source format can never change the resulting plan. EXPLAIN
     * detection and optimization happen at this statement level, identically for every source.
     */
    private static PlannedQuery planFrom(Statement statement, QueryConfig config) {
        PlanContext ctx = new PlanContext(config);

        boolean explain = false;
        Select select;
        if (statement instanceof ExplainStatement explainStatement) {
            explain = true;
            select = explainStatement.getStatement();
        } else if (statement instanceof Select s) {
            select = s;
        } else {
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "Only SELECT queries are supported; got "
                            + (statement == null ? "an empty statement"
                                    : statement.getClass().getSimpleName().toUpperCase()));
        }

        Operator rootOp = buildOperatorTree(ctx, select);

        // Ensure schemas are properly registered
        ensureAllSchemasRegistered(rootOp);

        String beforeText = explain ? PlanPrinter.print(rootOp) : null;

        // Apply query optimization if enabled
        if (config.useQueryOptimization()) {
            rootOp = QueryPlanOptimizer.optimize(ctx, rootOp);
        }

        String explainText = null;
        if (explain) {
            explainText = "=== Plan (as written) ===\n" + beforeText
                    + "\n=== Plan (optimized) ===\n" + PlanPrinter.print(rootOp);
        }

        return new PlannedQuery(rootOp, explainText);
    }

    /**
     * Trims a JSqlParser failure to its useful head: the unexpected token and position.
     * The full message appends every token the grammar would accept — hundreds of lines
     * of noise for an agent that just needs to see what was wrong.
     */
    private static String parserMessage(JSQLParserException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage() != null ? cause.getMessage() : "unparseable SQL";
        int cut = message.indexOf("\nWas expecting");
        return (cut > 0 ? message.substring(0, cut) : message).strip();
    }

    /**
     * Builds the unoptimized operator tree for a SELECT (the pipeline previously inlined
     * in parseStatement): scan, joins/selection, aggregation, projection, distinct/order,
     * limit.
     */
    private static Operator buildOperatorTree(PlanContext ctx, Select select) {
        Operator rootOp = createScanOperator(ctx, select);

        if (existJoinOp(select)) {
            rootOp = processJoins(ctx, rootOp, select);
        } else if (existSelectOp(select)) {
            rootOp = new SelectOperator(ctx, rootOp, select.getPlainSelect().getWhere());
        }

        rootOp = processGroupByAndAggregation(ctx, rootOp, select);
        rootOp = processProjection(ctx, rootOp, select);
        rootOp = processDistinctAndOrderBy(ctx, rootOp, select);
        rootOp = processLimit(ctx, rootOp, select);

        return rootOp;
    }

    /**
     * Creates a scan operator for the first table in the FROM clause.
     * @param ctx The per-query context
     * @param select The SQL SELECT statement
     * @return A ScanOperator for the first table
     */
    private static Operator createScanOperator(PlanContext ctx, Select select) {
        Table firstTable = (Table) select.getPlainSelect().getFromItem();
        return new ScanOperator(ctx, firstTable.getName());
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
    private static Operator processJoins(PlanContext ctx, Operator rootOp, Select select) {
        ExpressionPreprocessor preprocessor = new ExpressionPreprocessor(ctx);

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

            Operator rightOp = new ScanOperator(ctx, table.getName());
            if (ctx.config().useHashJoin() && HashJoinOperator.hasEquiConjunct(joinCondition)) {
                rootOp = new HashJoinOperator(ctx, rootOp, rightOp, joinCondition);
            } else {
                rootOp = new JoinOperator(ctx, rootOp, rightOp, joinCondition);
            }

            joinedTableNames.add(table.getName());

        }

        // Add any remaining selection conditions after joins
        if (!selectExpressions.isEmpty()) {
            rootOp = new SelectOperator(ctx, rootOp, combineExpression(selectExpressions));
        }

        return rootOp;
    }

    /**
     * Processes GROUP BY and aggregation operations.
     * Handles both queries with explicit GROUP BY and those with SUM/COUNT/AVG/MIN/MAX aggregates.
     * @param rootOp The operator tree so far
     * @param select The SELECT statement being processed
     * @return Updated operator tree with aggregation
     */
    private static Operator processGroupByAndAggregation(PlanContext ctx, Operator rootOp, Select select) {
        validateAggregateQuery(select);

        boolean hasGroupBy = existGroupByOp(select);
        boolean hasAggregates = existAggregate(select);

        if (!hasGroupBy && !hasAggregates) {
            return rootOp;
        }

        List<Column> groupByColumns = hasGroupBy ? extractGroupByColumns(select) : new ArrayList<>();
        List<AggregateCall> aggregateCalls = extractAggregateCalls(select);
        List<Column> outputColumns = hasGroupBy ? extractNonAggregateColumns(select) : new ArrayList<>();

        Set<Column> requiredColumns = getRequiredColumnsForAggregation(groupByColumns, aggregateCalls, select);

        // SELECT COUNT(*) FROM t with no WHERE/GROUP BY needs no columns at all;
        // a zero-column projection is meaningless, so skip it.
        if (!requiredColumns.isEmpty()) {
            List<Column> sortedColumns = new ArrayList<>(requiredColumns);
            sortedColumns.sort(Comparator
                    .comparing((Column c) -> c.getTable() != null ? c.getTable().getName() : "")
                    .thenComparing(Column::getColumnName));
            rootOp = new ProjectOperator(ctx, rootOp, sortedColumns);
        }

        return new AggregateOperator(ctx, rootOp, groupByColumns, aggregateCalls, outputColumns);
    }

    /**
     * Processes projection operations.
     * Adds a ProjectOperator if the query selects specific columns.
     * @param rootOp The operator tree so far
     * @param select The SELECT statement being processed
     * @return Updated operator tree with projection
     */
    private static Operator processProjection(PlanContext ctx, Operator rootOp, Select select) {
        // Add projection if needed (only if not GROUP BY or aggregate or if SELECT *)
        if (existProjectOp(select) && !existAggregate(select) && !existGroupByOp(select)) {
            rootOp = new ProjectOperator(ctx, rootOp, getProjectCols(select));
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
    private static Operator processDistinctAndOrderBy(PlanContext ctx, Operator rootOp, Select select) {
        // Add DISTINCT if needed
        if (existDistinctOp(select)) {
            rootOp = new DuplicateEliminationOperator(ctx, rootOp);
        }

        // Add ORDER BY if needed
        if (existSortOp(select)) {
            rootOp = new SortOperator(ctx, rootOp, getSortCols(select));
        }

        return rootOp;
    }

    /**
     * Applies the LIMIT clause, if present, as the topmost operator.
     * Only plain "LIMIT n" with a non-negative integer literal is supported.
     */
    private static Operator processLimit(PlanContext ctx, Operator rootOp, Select select) {
        Limit limit = select.getLimit();
        if (limit == null) {
            return rootOp;
        }
        if (limit.getOffset() != null) {
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "OFFSET is not supported; use plain LIMIT n");
        }
        if (limit.isLimitAll() || limit.isLimitNull()) {
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "LIMIT ALL / LIMIT NULL are not supported; use LIMIT n with n >= 0");
        }
        if (!(limit.getRowCount() instanceof LongValue rowCount) || rowCount.getValue() < 0) {
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "LIMIT requires a non-negative integer literal; got '"
                            + limit.getRowCount() + "'");
        }
        return new LimitOperator(ctx, rootOp, rowCount.getValue());
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
     * Checks if any aggregate function (SUM/COUNT/AVG/MIN/MAX) appears in the SELECT list.
     */
    private static boolean existAggregate(Select select) {
        for (SelectItem<?> item : select.getPlainSelect().getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof Function function
                    && AggregateFunction.fromFunctionName(function.getName()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the SELECT list of aggregate / GROUP BY queries at plan time — the engine
     * would otherwise silently drop unsupported items (or fail late with a generic runtime
     * error), which misleads agent callers. Rules:
     * - every SELECT item must be an aggregate call or a plain column;
     * - SELECT * cannot be combined with aggregates or GROUP BY;
     * - without GROUP BY, no bare columns may appear alongside aggregates;
     * - with GROUP BY, every selected bare column must appear in the GROUP BY list.
     */
    private static void validateAggregateQuery(Select select) {
        boolean hasAggregates = existAggregate(select);
        boolean hasGroupBy = existGroupByOp(select);
        if (!hasAggregates && !hasGroupBy) {
            return;
        }

        Set<String> groupByKeys = new HashSet<>();
        if (hasGroupBy) {
            for (Column column : extractGroupByColumns(select)) {
                groupByKeys.add(columnKey(column));
            }
        }

        for (SelectItem<?> item : select.getPlainSelect().getSelectItems()) {
            Expression expr = item.getExpression();

            if (expr instanceof Function function
                    && AggregateFunction.fromFunctionName(function.getName()) != null) {
                continue; // aggregate call — validated by extractAggregateCalls
            }
            if (expr instanceof AllColumns) {
                throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                        "SELECT * cannot be combined with aggregates or GROUP BY; "
                                + "list the columns explicitly");
            }
            if (expr instanceof Column column) {
                if (!hasGroupBy) {
                    throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                            "Non-aggregate column '" + column
                                    + "' in SELECT with aggregates requires GROUP BY");
                }
                if (!groupByKeys.contains(columnKey(column))) {
                    throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                            "Column '" + column + "' in SELECT must appear in GROUP BY");
                }
                continue;
            }
            throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                    "Unsupported SELECT item '" + expr + "' with aggregates or GROUP BY; "
                            + "only aggregate functions and grouped columns are allowed");
        }
    }

    /** Case-insensitive identity key for a column reference ("table.col" or bare "col"). */
    private static String columnKey(Column column) {
        String table = column.getTable() != null && column.getTable().getName() != null
                ? column.getTable().getName().toLowerCase() + "."
                : "";
        return table + column.getColumnName().toLowerCase();
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
            } else {
                throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                        "GROUP BY supports only column references; got '" + expr + "'");
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
            boolean isStar = function.isAllColumns()
                    || function.getParameters() == null
                    || function.getParameters().isEmpty()
                    || (function.getParameters().size() == 1
                            && function.getParameters().get(0) instanceof AllColumns);
            if (isStar) {
                if (aggregateFunction != AggregateFunction.COUNT) {
                    throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                            function.getName() + "(*) is not supported; only COUNT(*) may use '*'");
                }
                argument = null;
            } else {
                if (function.getParameters().size() != 1) {
                    throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                            function.getName() + " expects exactly one argument, got "
                                    + function.getParameters().size() + ": '" + function + "'");
                }
                argument = (Expression) function.getParameters().get(0);
                // The engine resolves columns by table qualifier throughout (projection,
                // schema keys); an unqualified argument would fail later with an opaque
                // NPE, so reject it here with a usable message instead
                ColumnExtractor extractor = new ColumnExtractor();
                argument.accept(extractor);
                for (Column argColumn : extractor.getColumns()) {
                    if (argColumn.getTable() == null || argColumn.getTable().getName() == null) {
                        throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                                "Aggregate arguments must use qualified column names "
                                        + "(table.column): '" + function + "'");
                    }
                }
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
                throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                        "ORDER BY supports only column references; got '" + orderByElement + "'");
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
                throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                        "SELECT supports only column references or aggregate calls; got '" + exp + "'");
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
                throw new QueryExecutionException(ErrorCode.UNSUPPORTED_SQL,
                        "Unsupported FROM item '" + join.getRightItem()
                                + "'; only plain tables can be joined");
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
