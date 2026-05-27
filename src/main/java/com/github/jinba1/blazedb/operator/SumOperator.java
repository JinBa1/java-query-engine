package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.util.*;

/**
 * The SumOperator implements the GROUP BY operation with SUM aggregation in SQL.
 * It is a blocking operator that reads all tuples from its child, groups them
 * by the specified columns, and calculates SUM aggregates for each group.
 */
public class SumOperator extends Operator {

    // Grouping columns and their resolved indices
    private List<Column> groupByColumns;
    private List<Integer> groupByIndices;

    // SUM aggregate expressions and their evaluators
    private List<Expression> sumExpressions;
    private List<ExpressionEvaluator> evaluators;

    // Output columns (group by columns that should be in the output)
    private List<Column> outputColumns;
    private List<Integer> outputIndices;

    // Map of groups and their aggregate values
    private Map<List<Integer>, List<Integer>> groupAggregates;

    // Iterator for returning grouped results
    private Iterator<Map.Entry<List<Integer>, List<Integer>>> resultIterator;

    // Flag to track if all input has been processed
    private boolean processed;

    private String intermediateSchemaId;
    private boolean schemaRegistered = false;

    /**
     * Constructs a SumOperator with the specified child operator, grouping columns,
     * SUM expressions, and output columns.
     * @param child The child operator from which to read tuples
     * @param groupByColumns The columns to group by
     * @param sumExpressions The SUM aggregate expressions
     * @param outputColumns The columns to include in the output (subset of groupByColumns)
     */
    public SumOperator(Operator child, List<Column> groupByColumns, List<Expression> sumExpressions, List<Column> outputColumns) {
        this.child = child;
        this.groupByColumns = groupByColumns;
        this.sumExpressions = sumExpressions;
        this.outputColumns = outputColumns;

        this.groupByIndices = new ArrayList<>();
        this.outputIndices = new ArrayList<>();
        this.evaluators = new ArrayList<>();
        this.groupAggregates = new HashMap<>();
        this.processed = false;
        // Ensure child schema is registered
        this.child.ensureSchemaRegistered();

        // Register our schema
        registerSchema();

        // Initialize evaluators for each SUM expression
        String schemaId = child.propagateSchemaId();
        for (int i = 0; i < sumExpressions.size(); i++) {
            this.evaluators.add(new ExpressionEvaluator(schemaId));
        }

        // Resolve column indices
        resolveColumnIndices();

    }

    /**
     * Resolves the indices of group by columns and output columns.
     * This method maps column references to their actual positions in the input tuples
     * using the schema information from the child operator.
     * The resolved indices are stored in groupByIndices and outputIndices for use
     * during tuple processing and result construction.
     */
    private void resolveColumnIndices() {

        String schemaId = child.propagateSchemaId();
        groupByIndices.clear();
        outputIndices.clear();

        // Debug output to verify column resolution

        // Resolve group by column indices
        this.groupByIndices = resolveColumnIndices(groupByColumns, schemaId, groupByIndices);
        this.outputIndices = resolveColumnIndices(outputColumns, schemaId, outputIndices);
    }

    /**
     * Returns the next tuple from the grouped and aggregated results.
     * On first call, reads and processes all tuples from the child operator
     * to compute group-by aggregates.
     * For GROUP BY queries, the returned tuple contains the group-by column values
     * followed by the aggregate values. For queries without GROUP BY, the returned
     * tuple contains only the aggregate values.
     * @return The next result tuple, or null if no more results are available
     */
    @Override
    public Tuple getNextTuple() {
        // Process all tuples from child on first call
        if (!processed) {
            processChildTuples();
        }

        // Return the next result if available
        if (resultIterator.hasNext()) {
            Map.Entry<List<Integer>, List<Integer>> entry = resultIterator.next();
            List<Integer> groupKeys = entry.getKey();
            List<Integer> aggregateValues = entry.getValue();

            // Construct the result tuple from output columns and aggregate values
            ArrayList<Integer> resultAttributes = new ArrayList<>();

            // If no GROUP BY, just return aggregate values
            if (groupByColumns.isEmpty()) {
                resultAttributes.addAll(aggregateValues);
                tupleCounter++;
                return new Tuple(resultAttributes);
            }

            // Add selected group by columns to the result
            for (Integer outputIndex : outputIndices) {
                int groupKeyIndex = groupByIndices.indexOf(outputIndex);
                if (groupKeyIndex != -1) {
                    resultAttributes.add(groupKeys.get(groupKeyIndex));
                } else {
                    throw new RuntimeException("Output column not found in group by columns");
                }
            }

            // Add aggregate values to the result
            resultAttributes.addAll(aggregateValues);

            tupleCounter++;
            return new Tuple(resultAttributes);
        }

        return null;
    }

    /**
     * Processes all tuples from the child operator, groups them, and computes aggregates.
     * This is a blocking operation that reads all input tuples before producing any output.
     * For each tuple read from the child:
     * 1. Extract group key values (if any)
     * 2. Get or create aggregate values for this group
     * 3. Evaluate each SUM expression and add to the appropriate aggregate
     * After processing all tuples, an iterator is initialized to return the results.
     */
    private void processChildTuples() {
        Tuple tuple;
        while ((tuple = child.getNextTuple()) != null) {
            // Extract group key values (empty list if no grouping)
            List<Integer> groupKey = new ArrayList<>();
            for (Integer index : groupByIndices) {
                groupKey.add(tuple.getAttribute(index));
            }

            // Get or create aggregates for this group
            List<Integer> aggregates = groupAggregates.computeIfAbsent(groupKey, k ->
                    new ArrayList<>(Collections.nCopies(sumExpressions.size(), 0)));

            // Update aggregate values for this group
            for (int i = 0; i < sumExpressions.size(); i++) {
                Expression sumExpr = sumExpressions.get(i);
                ExpressionEvaluator evaluator = evaluators.get(i);

                if (sumExpr instanceof Function) {
                    Function function = (Function) sumExpr;
                    if (Constants.SUM_FUNCTION_NAME.equalsIgnoreCase(function.getName())) {
                        Expression innerExpr = (Expression) function.getParameters().get(0);
                        // Evaluate the expression for this tuple
                        int value = evaluator.evaluateValue(innerExpr, tuple);
                        // Add to the current aggregate value
                        aggregates.set(i, aggregates.get(i) + value);
                    }
                }
            }
        }

        // Initialize iterator for returning results
        resultIterator = groupAggregates.entrySet().iterator();
        processed = true;
    }

    /**
     * Resets the operator to its initial state.
     * Clears all processed aggregates and resets the child operator.
     * After reset, the next call to getNextTuple() will reprocess all tuples.
     */
    @Override
    public void reset() {
        child.reset();
        groupAggregates.clear();
        processed = false;
    }

    /**
     * Propagates the schema ID for this operator.
     * Ensures the schema is registered before returning.
     * @return The schema ID for this operator after alternation.
     */
    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    /**
     * Registers the schema for this operator.
     * Creates a schema for the aggregation result, mapping output column names and
     * aggregate function names to appropriate indices.
     * The schema includes:
     * 1. Group by columns selected for output
     * 2. SUM aggregates with descriptive names
     * Transformation details are recorded to track how the schema was derived,
     * which helps with column resolution in parent operators.
     */
    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;

        // Create schema for aggregation result
        Map<String, Integer> resultSchema = new HashMap<>();
        Map<String, String> transformationDetails = new HashMap<>();

        int colIndex = 0;
        String childSchemaId = child.propagateSchemaId();

        // Add group by columns
        if (!groupByColumns.isEmpty()) {
            for (Column col : outputColumns) {
                String tableName = col.getTable().getName();
                String columnName = col.getColumnName().toLowerCase();
                String key = tableName + "." + columnName;

                resultSchema.put(key, colIndex);

                // Record source column
                Integer sourceIndex = DBCatalog.getInstance().resolveColumnWithOrigins(childSchemaId, tableName, columnName);
                if (sourceIndex == null) {
                    throw new RuntimeException("Column " + tableName + "." + columnName +
                            " not found in schema " + childSchemaId);
                }
                transformationDetails.put(key, "group_by:" + sourceIndex);

                colIndex++;
            }
        }

        // Add aggregate functions
        for (int i = 0; i < sumExpressions.size(); i++) {
            Expression sumExpr = sumExpressions.get(i);
            if (sumExpr instanceof Function) {
                Function function = (Function) sumExpr;
                String functionName = function.getName();
                ExpressionList params = function.getParameters();
                Expression param = (Expression) params.get(0);

                String aggregateKey;
                if (param instanceof Column) {
                    Column col = (Column) param;
                    String tableName = col.getTable().getName();
                    String columnName = col.getColumnName().toLowerCase();
                    aggregateKey = functionName + "(" + tableName + "." + columnName + ")";
                } else {
                    aggregateKey = functionName + "(" + i + ")";
                }

                resultSchema.put(aggregateKey, colIndex);
                transformationDetails.put(aggregateKey, "aggregate:" + i);
                colIndex++;
            }
        }

        // Register schema
        intermediateSchemaId = DBCatalog.getInstance().registerSchemaWithTransformation(
                resultSchema,
                childSchemaId,
                SchemaTransformationType.AGGREGATION,
                transformationDetails
        );

        schemaRegistered = true;
    }
}