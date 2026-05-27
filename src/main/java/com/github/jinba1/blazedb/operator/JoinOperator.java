package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.*;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The JoinOperator implements the tuple nested loop join algorithm for relational query processing.
 * This is the only binary operator implemented.
 * This class joins tuples from two input operators (outer and inner) based on an optional join condition.
 * If no join condition is provided, the operator performs a cross product of the two inputs.
 * @see Operator
 * @see ExpressionEvaluator
 */
public class JoinOperator extends Operator {
    private Operator outerChild;
    private Expression expression;
    private ExpressionEvaluator evaluator;

    private Tuple currentOuterTuple; // used to track progress

    /**
     * Constructs a JoinOperator with specified outer child, inner child, and join condition.
     * The operator combines tuples from both children according to the join condition.
     * If the join condition is null, a cross product is performed.
     * @param outerChild The outer (left) child operator, scanned once.
     * @param innerChild The inner (right) child operator, scanned multiple times.
     * @param expression The join condition expression, or null for cross product.
     */
    public JoinOperator(Operator outerChild, Operator innerChild, Expression expression) {
        this.child = innerChild;
        this.outerChild = outerChild;
        this.expression = expression;
        currentOuterTuple = null;
        this.child.ensureSchemaRegistered();
        this.outerChild.ensureSchemaRegistered();

        registerSchema();

        this.evaluator = new ExpressionEvaluator(intermediateSchemaId);
    }
    /**
     * Returns the next tuple in the join result.
     * Implements the nested loop join algorithm:
     * 1. For each tuple from the outer child
     * 2. Scan all tuples from the inner child
     * 3. For each pair of tuples, combine them and evaluate the join condition
     * 4. Return matching tuples
     * The operator maintains state between calls to track the current outer tuple position.
     * @return A combined tuple that satisfies the join condition, or null if no more matching tuples
     */
    @Override
    public Tuple getNextTuple() {

        if (currentOuterTuple == null) {
            currentOuterTuple = outerChild.getNextTuple();
            if (currentOuterTuple == null) {
                return null;
            }
            child.reset(); // reset inner child for next outer tuple
        }
        while (true) {
            Tuple innerTuple = child.getNextTuple();
//                    ", columns: " + (innerTuple == null ? "n/a" : innerTuple.getTuple().size()));
            if (innerTuple == null) {
                currentOuterTuple = outerChild.getNextTuple();
                if (currentOuterTuple == null) {
                    return null;  // no more tuples from either children
                }
                child.reset();
                continue; // reset inner child for next outer tuple
            }
            Tuple combined = combineTuples(currentOuterTuple, innerTuple);

//                    ", columns: " + combined.getTuple().size());

            if (expression == null  || evaluator.evaluate(expression, combined)) {
                tupleCounter++;
                return combined;  // pass join condition if having one
            }
        }

    }

    /**
     * Resets the join operator to its initial state.
     * Resets both child operators and clears any stored state.
     */
    @Override
    public void reset() {
        outerChild.reset();
        child.reset();
    }
    /**
     * Propagates the identifier for the joined schema.
     * This is primarily used for schema tracking.
     * @return The unique identifier for joined schema.
     */
    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    /**
     * Combines tuples from the left and right child into a single tuple.
     * The combined tuple contains all attributes from the left tuple followed by all attributes from the right tuple.
     * @param leftTuple The tuple from the outer child
     * @param rightTuple The tuple from the inner child
     * @return A new tuple containing all attributes from both input tuples
     */
    private Tuple combineTuples(Tuple leftTuple, Tuple rightTuple) {
        ArrayList<Integer> combinedAttributes = new ArrayList<>();
        combinedAttributes.addAll(leftTuple.getTuple());
        combinedAttributes.addAll(rightTuple.getTuple());
        return new Tuple(combinedAttributes);
    }

    /**
     * Returns the outer child operator of this join.
     * @return The outer (left) child operator
     */
    public Operator getOuterChild() {
        return outerChild;
    }

    /**
     * Sets the outer child operator of this join.
     * Updates join condition evaluation if necessary.
     * @param outerChild The new outer child operator
     */
    public void setOuterChild(Operator outerChild) {
        this.outerChild = outerChild;
        updateJoinCondition();
    }

    /**
     * Sets the inner child operator of this join.
     * Overrides the parent class method to update join condition evaluation.
     * @param innerChild The new inner child operator
     */
    public void setChild(Operator innerChild) {
        this.child = innerChild;
        updateJoinCondition();
    }

    /**
     * Updates the join condition evaluator when child operators change.
     * This ensures correct schema resolution for the join condition.
     */
    private void updateJoinCondition() {
        if (this.expression == null) return;

        // Update schema ID for the evaluator
        if (this.intermediateSchemaId != null) {
            this.evaluator = new ExpressionEvaluator(intermediateSchemaId);
        }
    }

    /**
     * Registers the schema for this join operator.
     * Creates a combined schema from both child operators and registers it with DBCatalog.
     * Records transformation details to track how the schema was derived.
     */
    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;

        String leftSchemaId = outerChild.propagateSchemaId();
        String rightSchemaId = child.propagateSchemaId();

        // Get source schemas
        Map<String, Integer> leftSchemaMap = getSchemaMap(leftSchemaId);
        Map<String, Integer> rightSchemaMap = getSchemaMap(rightSchemaId);
        if (leftSchemaMap == null || rightSchemaMap == null) {
            throw new RuntimeException("Could not retrieve schemas for join");
        }

        // Create combined schema
        Map<String, Integer> joinedSchema = new HashMap<>();
        Map<String, String> transformationDetails = new HashMap<>();

        // Calculate left tuple size for offset
        int leftTupleSize = 0;
        for (Integer index : leftSchemaMap.values()) {
            leftTupleSize = Math.max(leftTupleSize, index + 1);
        }

        // Add entries from both sources
        addSchemaEntries(joinedSchema, transformationDetails, leftSchemaMap, leftSchemaId, 0);
        addSchemaEntries(joinedSchema, transformationDetails, rightSchemaMap, rightSchemaId, leftTupleSize);

        // Add join condition information
        if (expression != null) {
            transformationDetails.put("join_condition", expression.toString());
        }

        // Register with transformation details
        intermediateSchemaId = DBCatalog.getInstance().registerSchemaWithTransformation(
                joinedSchema,
                null, // No single parent
                SchemaTransformationType.JOIN,
                transformationDetails
        );

        // Register both parents
        DBCatalog catalog = DBCatalog.getInstance();
        catalog.addParentSchema(intermediateSchemaId, leftSchemaId);
        catalog.addParentSchema(intermediateSchemaId, rightSchemaId);
        schemaRegistered = true;
    }

    /**
     * Retrieves the schema map for a given schema ID.
     * Helper method to access schema information from either intermediate or base schemas.
     * @param schemaId The schema ID to retrieve the map for
     * @return A map from column names to indices for the specified schema
     */
    private Map<String, Integer> getSchemaMap(String schemaId) {
        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            return DBCatalog.getInstance().getIntermediateSchema(schemaId);
        } else {
            return DBCatalog.getInstance().getDBSchemata(schemaId);
        }
    }

    /**\
     * Helper function for updating schema.
     * Adds schema entries from a source schema to the joined schema.
     * Handles both qualified and unqualified column names with appropriate offsets.
     * @param joinedSchema The target joined schema map to populate
     * @param transformationDetails Map to record transformation details
     * @param sourceSchema The source schema to add entries from
     * @param sourceSchemaId The ID of the source schema
     * @param offset The index offset to apply to source column indices
     */
    private void addSchemaEntries(Map<String, Integer> joinedSchema,
                                  Map<String, String> transformationDetails,
                                  Map<String, Integer> sourceSchema,
                                  String sourceSchemaId,
                                  int offset) {

        // First determine if this is a temp schema that represents a base table
        String originalTableName = null;
        if (sourceSchemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            // Try to find original table through parent schemas
            String parentId = DBCatalog.getInstance().getParentSchemaId(sourceSchemaId);
            if (parentId != null && !parentId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
                originalTableName = parentId;
            }
        } else {
            // Direct base table
            originalTableName = sourceSchemaId;
        }

        for (Map.Entry<String, Integer> entry : sourceSchema.entrySet()) {
            String columnKey = entry.getKey();
            Integer sourceIndex = entry.getValue();
            Integer targetIndex = sourceIndex + offset;

            // Always add with temp schema prefix for consistency
            if (columnKey.contains(".")) {
                // For columns already with table qualification
                joinedSchema.put(columnKey, targetIndex);

                // Extract the column name part
                String columnName = columnKey.substring(columnKey.indexOf('.') + 1);

                // Also add with original table name if available
                if (originalTableName != null) {
                    joinedSchema.put(originalTableName + "." + columnName, targetIndex);
                }
            } else {
                // For unqualified columns
                joinedSchema.put(sourceSchemaId + "." + columnKey, targetIndex);

                // Also add with original table name if available
                if (originalTableName != null) {
                    joinedSchema.put(originalTableName + "." + columnKey, targetIndex);
                }
            }

            // Record mapping in transformation details
            transformationDetails.put(columnKey, sourceSchemaId + ":" + sourceIndex);
        }
    }
    /**
     * Returns the join condition expression.
     * @return The expression used as the join condition, or null for cross product
     */
    public Expression getJoinCondition() {
        return expression;
    }

    /**
     * Recursively update schema information from bottom up.
     * First update both child operators.
     */
    @Override
    public void updateSchema() {

        this.schemaRegistered = false;
        // Update children first
        if (this.outerChild != null) {
            this.outerChild.updateSchema();
        }
        if (this.child != null) {
            this.child.updateSchema();
        }

        // Re-register schema based on updated children
        registerSchema();

        // MISSING STEP: Update join condition expressions to match new schema
        // Create a new evaluator with the updated schema
        this.evaluator = new ExpressionEvaluator(intermediateSchemaId);

    }
}
