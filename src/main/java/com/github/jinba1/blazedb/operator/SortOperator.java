package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.Tuple;
import com.github.jinba1.blazedb.TupleComparator;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SortOperator implements the ORDER BY operation in SQL queries.
 * This operator applies a specified ordering to a sequence of tuples returned by its child operator.
 * SortOperator is a blocking operator, meaning it must read all tuples from the child operator
 * before producing any output.
 * The operator sorts in ascending order only.
 * The implementation buffers all tuples from the child operator in memory, sorts them using
 * a TupleComparator based on the specified sort columns.
 * The sort order is determined by the provided list of columns. If two tuples have equal values
 * for the first column, they are sorted by the second column, and so on.
 */
public class SortOperator extends Operator {

    private List<Column> sortColumns;
    private List<Integer> resolvedIndices;
    private List<Tuple> tupleBuffer;
    private boolean buffered;
    private boolean indicesResolved;
    private int currentTupleIndex;
    private TupleComparator comparator;

    /**
     * Constructs a SortOperator with the specified child operator and sort columns.
     * The operator will read all tuples from the child, sort them according to the
     * specified columns, and return them in sorted order.
     * @param child The child operator from which to read tuples
     * @param sortColumns The columns to sort by, in order of precedence
     */
    public SortOperator(Operator child, List<Column> sortColumns) {
        this.sortColumns = sortColumns;
        this.child = child;
        this.tupleBuffer = new ArrayList<>();
        currentTupleIndex = 0;
        buffered = false;
        indicesResolved = false;

        // Ensure child schema is registered
        this.child.ensureSchemaRegistered();

        // Register our schema
        registerSchema();
    }

    /**
     * Returns the next tuple in sort order.
     * On first call, reads all tuples from the child operator, sorts them,
     * and begins returning them one by one.
     * Subsequent calls continue returning sorted tuples until all are returned.
     * @return The next tuple in sort order, or null if no more tuples are available
     */
    @Override
    public Tuple getNextTuple() {
        if (!buffered) {
            bufferTuple();
        }

        if (currentTupleIndex >= tupleBuffer.size()) {
            return null;
        }

        Tuple currentTuple = tupleBuffer.get(currentTupleIndex);
        currentTupleIndex += 1;
        //tupleCounter++;
        return currentTuple;
    }

    /**
     * Resets the operator to its initial state.
     * After reset, the next call to getNextTuple() will return the first
     * tuple in the sorted order again.
     * Note that this does not reread tuples from the child operator.
     */
    @Override
    public void reset() {
        currentTupleIndex = 0;
    }
    /**
     * Propagates the schema id from the child operator.
     * This is primarily used for schema tracking.
     * @return The unique identifier for the schema used.
     */
    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    /**
     * Buffers all tuples from the child operator and sorts them.
     * This is a blocking operation that reads all input tuples before sorting.
     * The method:
     * 1. Reads all tuples from the child operator
     * 2. Resolves column indices for sorting if not already done
     * 3. Creates a comparator based on the resolved indices
     * 4. Sorts the tuples
     * After this method is called, the tuples are ready to be returned
     * in sorted order via getNextTuple().
     */
    private void bufferTuple() {
        if (!buffered) {
            Tuple tuple = child.getNextTuple();

            while (tuple != null) {
                tupleBuffer.add(tuple);  // Only add non-null tuples
                tuple = child.getNextTuple();
            }

            if (!indicesResolved) {
                resolveColumnIndices();
            }

            comparator = new TupleComparator(resolvedIndices);
            tupleBuffer.sort(comparator);

            buffered = true;
        }
    }
    /**
     * Resolves the indices of sort columns.
     * This method maps column references to their actual positions in the input tuples
     * using the schema information from the child operator.
     * The resolved indices are stored in resolvedIndices for use by the comparator
     * during tuple sorting.
     * If the column indices have already been resolved, this method does nothing.
     */
    private void resolveColumnIndices() {
        if (indicesResolved) {
            return;
        }

        String schemaId = propagateSchemaId();
        this.resolvedIndices = resolveColumnIndices(sortColumns, schemaId, null);

        indicesResolved = true;

    }
    /**
     * Registers the schema for this operator.
     * Creates a schema identical to the child operator's schema, since sorting
     * does not change the structure of tuples, only their order.
     * Transformation details are recorded to track the sort columns,
     */
    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;

        // Add details about sort columns
        Map<String, String> transformationDetails = new HashMap<>();
        for (int i = 0; i < sortColumns.size(); i++) {
            Column col = sortColumns.get(i);
            transformationDetails.put("sort_" + i, col.getTable().getName() +
                    "." + col.getColumnName().toLowerCase());
        }

        intermediateSchemaId = registerPassthroughSchema(
                child,
                transformationDetails
        );

        schemaRegistered = true;
    }

    public List<Column> getSortColumns() {
        return sortColumns;
    }
}
