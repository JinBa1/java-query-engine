package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.Tuple;

import java.util.*;

/**
 * The DuplicateEliminationOperator implements the DISTINCT operation in SQL.
 * It eliminates duplicate tuples from its child operator's output.
 * This implementation uses a hash-based approach for duplicate detection.
 */
public class DuplicateEliminationOperator extends Operator {

    // Buffer to store unique tuples after they've been processed
    private List<Tuple> uniqueTuples;
    // Iterator for returning unique tuples
    private Iterator<Tuple> tupleIterator;
    // Flag to track if tuples have been processed
    private boolean processed;

    /**
     * Constructs a DuplicateEliminationOperator with the given child operator.
     * @param child The child operator from which to read tuples
     */
    public DuplicateEliminationOperator(PlanContext ctx, Operator child) {
        super(ctx);
        this.child = child;
        this.uniqueTuples = new ArrayList<>();
        this.processed = false;

        // Ensure child schema is registered
        this.child.ensureSchemaRegistered();

        // Register our schema
        registerSchema();
    }

    /**
     * Returns the next unique tuple from the child operator's output.
     * On first call, reads all tuples from the child and eliminates duplicates.
     * @return The next unique tuple, or null if no more unique tuples exist
     */
    @Override
    public Tuple getNextTuple() {
        // Process all tuples from child on first call
        if (!processed) {
            processChildTuples();
        }

        // Return the next unique tuple if available
        if (tupleIterator.hasNext()) {
            countTuple();
            return tupleIterator.next();
        }

        return null;
    }

    /**
     * Processes all tuples from the child operator, eliminating duplicates.
     * Uses a HashSet for efficient duplicate detection.
     */
    private void processChildTuples() {
        // Use a set to eliminate duplicates based on tuple content
        Set<Tuple> uniqueSet = new HashSet<>();

        // Process each tuple from the child operator
        Tuple childTuple;
        while ((childTuple = child.getNextTuple()) != null) {
            checkBudgetDeadline(); // dedup emits nothing; the timeout must still reach it
            // The HashSet will use Tuple's equals() and hashCode() methods
            // to determine if a tuple is already in the set
            if (uniqueSet.add(childTuple)) {
                // Only add to our result list if it's a new unique tuple
                uniqueTuples.add(childTuple);
            }
        }

        // Initialize iterator for returning results
        tupleIterator = uniqueTuples.iterator();
        processed = true;
    }

    /**
     * Resets the operator to its initial state.
     * Clears all processed unique tuples and resets the child operator.
     */
    @Override
    public void reset() {
        child.reset();
        uniqueTuples.clear();
        processed = false;
    }
    /**
     * Propagates the schema ID from the child operator.
     * @return The schema ID from the child operator
     */
    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    @Override
    public String describe() {
        return "Distinct";
    }

    /**
     * Registers the schema for this operator.
     * Creates a schema identical to the child operator's schema, since duplicate
     * elimination does not change the structure of tuples.
     * Transformation details are recorded.
     */
    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;

        // Create details about distinct operation (minimal)
        Map<String, String> transformationDetails = new HashMap<>();
        transformationDetails.put("distinct", "true");

        intermediateSchemaId = registerPassthroughSchema(
                child,
                transformationDetails
        );

        schemaRegistered = true;
    }
}