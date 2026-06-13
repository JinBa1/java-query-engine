package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.*;
import net.sf.jsqlparser.schema.Column;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The AggregateOperator implements GROUP BY with SUM/COUNT/AVG/MIN/MAX aggregation in SQL.
 * It is a blocking operator that reads all tuples from its child, groups them
 * by the specified columns, and computes aggregates for each group via accumulators.
 */
public class AggregateOperator extends Operator {

    // Grouping columns and their resolved indices
    private List<Column> groupByColumns;
    private List<Integer> groupByIndices;

    // Aggregate calls and their argument evaluators (one evaluator per call)
    private List<AggregateCall> aggregateCalls;
    private List<ExpressionEvaluator> evaluators;

    // Output columns (group by columns that should be in the output)
    private List<Column> outputColumns;
    private List<Integer> outputIndices;

    // Map of groups and their accumulators (one accumulator per call per group)
    private Map<List<Value>, List<Accumulator>> groupAggregates;

    // Iterator for returning grouped results
    private Iterator<Map.Entry<List<Value>, List<Accumulator>>> resultIterator;

    // Flag to track if all input has been processed
    private boolean processed;

    /**
     * Constructs an AggregateOperator with the specified child operator, grouping columns,
     * aggregate calls, and output columns.
     * @param child The child operator from which to read tuples
     * @param groupByColumns The columns to group by
     * @param aggregateCalls The aggregate calls (SUM/COUNT/AVG/MIN/MAX) in SELECT-list order
     * @param outputColumns The columns to include in the output (subset of groupByColumns)
     */
    public AggregateOperator(PlanContext ctx, Operator child, List<Column> groupByColumns,
                             List<AggregateCall> aggregateCalls, List<Column> outputColumns) {
        super(ctx);
        this.child = child;
        this.groupByColumns = groupByColumns;
        this.aggregateCalls = aggregateCalls;
        this.outputColumns = outputColumns;

        this.groupByIndices = new ArrayList<>();
        this.outputIndices = new ArrayList<>();
        this.evaluators = new ArrayList<>();
        this.groupAggregates = new HashMap<>();
        this.processed = false;
        this.child.ensureSchemaRegistered();

        registerSchema();

        String schemaId = child.propagateSchemaId();
        for (int i = 0; i < aggregateCalls.size(); i++) {
            this.evaluators.add(new ExpressionEvaluator(ctx, schemaId));
        }

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
            Map.Entry<List<Value>, List<Accumulator>> entry = resultIterator.next();
            List<Value> groupKeys = entry.getKey();

            // Construct the result tuple from output columns and aggregate values
            ArrayList<Value> resultAttributes = new ArrayList<>();

            // If no GROUP BY, just return aggregate values
            if (groupByColumns.isEmpty()) {
                for (Accumulator accumulator : entry.getValue()) {
                    resultAttributes.add(accumulator.result());
                }
                countTuple();
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
            for (Accumulator accumulator : entry.getValue()) {
                resultAttributes.add(accumulator.result());
            }

            countTuple();
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
     * 3. Evaluate each aggregate call's argument and fold it into the group's accumulators
     * After processing all tuples, an iterator is initialized to return the results.
     */
    private void processChildTuples() {
        Tuple tuple;
        while ((tuple = child.getNextTuple()) != null) {
            checkBudgetDeadline(); // accumulation emits nothing; the timeout must still reach it
            List<Value> groupKey = new ArrayList<>();
            for (Integer index : groupByIndices) {
                groupKey.add(tuple.getAttribute(index));
            }

            List<Accumulator> accumulators = groupAggregates.computeIfAbsent(groupKey, k -> {
                List<Accumulator> created = new ArrayList<>(aggregateCalls.size());
                for (AggregateCall call : aggregateCalls) {
                    created.add(Accumulator.create(call));
                }
                return created;
            });

            for (int i = 0; i < aggregateCalls.size(); i++) {
                AggregateCall call = aggregateCalls.get(i);
                Value value = call.argument() == null
                        ? null
                        : evaluators.get(i).evaluateValue(call.argument(), tuple);
                accumulators.get(i).add(value);
            }
        }

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

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Aggregate[");
        if (!groupByColumns.isEmpty()) {
            sb.append("group by: ").append(groupByColumns.stream().map(Column::toString)
                    .collect(Collectors.joining(", ")));
        }
        if (!aggregateCalls.isEmpty()) {
            if (!groupByColumns.isEmpty()) {
                sb.append("; ");
            }
            sb.append("calls: ").append(aggregateCalls.stream().map(AggregateCall::schemaKey)
                    .collect(Collectors.joining(", ")));
        }
        return sb.append("]").toString();
    }

    /**
     * Registers the schema for this operator.
     * Creates a schema for the aggregation result, mapping output column names and
     * aggregate function names to appropriate indices.
     * The schema includes:
     * 1. Group by columns selected for output
     * 2. Aggregate calls keyed by their precomputed schema keys
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
                Integer sourceIndex = ctx.resolveColumnWithOrigins(childSchemaId, tableName, columnName);
                if (sourceIndex == null) {
                    throw new RuntimeException("Column " + tableName + "." + columnName +
                            " not found in schema " + childSchemaId);
                }
                transformationDetails.put(key, "group_by:" + sourceIndex);

                colIndex++;
            }
        }

        // Add aggregate calls (keys precomputed by the planner, e.g. "SUM(student.b)")
        for (int i = 0; i < aggregateCalls.size(); i++) {
            AggregateCall call = aggregateCalls.get(i);
            resultSchema.put(call.schemaKey(), colIndex);
            transformationDetails.put(call.schemaKey(), "aggregate:" + i);
            colIndex++;
        }

        // Register schema
        intermediateSchemaId = ctx.registerSchemaWithTransformation(
                resultSchema,
                childSchemaId,
                SchemaTransformationType.AGGREGATION,
                transformationDetails
        );

        schemaRegistered = true;
    }
}