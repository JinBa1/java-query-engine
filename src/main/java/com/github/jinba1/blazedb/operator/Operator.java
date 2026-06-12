package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.QueryBudget;
import com.github.jinba1.blazedb.QueryExecutionException;
import com.github.jinba1.blazedb.SchemaTransformationType;
import com.github.jinba1.blazedb.Tuple;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract class representing a relational operator in BlazeDB, following the iterator model.
 * This class defines the core functionality every operator must provide.
 * Every specific relational operator (e.g., Scan, Select, Project, Join, Sort, Aggregate)
 * extends this abstract class and implements the methods defined here. The relational operators
 * process tuples in a pipeline fashion, allowing results to flow from the leaf operators up to
 * the root operator.
 */
public abstract class Operator {

    // Child operator
    protected Operator child;

    // Schema information
    protected String intermediateSchemaId = null;
    protected boolean schemaRegistered = false;

    // Tuple counter for performance benchmarking
    protected long tupleCounter = 0;

    // Per-query execution budget; null means unlimited (default)
    protected QueryBudget budget = null;

    // Per-query context: schema registry + config. Never null.
    protected final PlanContext ctx;

    protected Operator(PlanContext ctx) {
        this.ctx = ctx;
    }

    /** The per-query context this operator was planned under. */
    public final PlanContext getContext() {
        return ctx;
    }

    /**
     * Retrieves the next tuple from the iterator.
     * @return A Tuple object representing the row of data, or NULL if EOF reached.
     */
    public abstract Tuple getNextTuple();

    /**
     * Gets the number of tuples processed by this operator.
     * @return The tuple count.
     */
    public long getTupleCount() {
        return tupleCounter;
    }

    /**
     * Resets the tuple counter to zero.
     */
    public void resetTupleCount() {
        tupleCounter = 0;
    }

    /**
     * Counts one emitted tuple and charges the query budget, if any.
     * Every operator calls this instead of incrementing tupleCounter directly,
     * so a budget observes total work across the whole tree.
     */
    protected final void countTuple() {
        tupleCounter++;
        if (budget != null) {
            budget.charge();
        }
    }

    /**
     * Attaches a query budget to this operator and its entire subtree.
     * @param budget The budget shared by all operators of one query
     */
    public void attachBudget(QueryBudget budget) {
        this.budget = budget;
        if (hasChild()) {
            child.attachBudget(budget);
        }
    }

    /**
     * Resets the iterator to the start.
     */
    public abstract void reset();

    /**
     * One-line, human-readable description of this operator for plan rendering
     * (e.g. "Scan[Sales]", "Select[Sales.qty > 5]"). No tree structure, no newlines.
     */
    public abstract String describe();
    /**
     * Propagates and retrieves the schema ID used to track schema transformations.
     * This method ensures schema consistency throughout the query evaluation. Each operator must appropriately propagate
     * schema identifiers, particularly after transformations like projections and joins.
     *
     * @return A Unique identifier representing the current operator's output schema.
     */
    public abstract String propagateSchemaId();

    /**
     * Get the child of this operator.
     * @return An operator instance that is the child of the current operator.
     */
    public final Operator getChild() {
        return child;
    }

    /**
     * Checks if this operator has a child operator.
     * @return True if this operator has a child, false otherwise.
     */
    public final boolean hasChild() {
        return child != null;
    }

    /**
     * Set the child of this operator.
     * @see com.github.jinba1.blazedb.QueryPlanOptimizer used during optimisations.
     * @param child An operator instance to be the new child of this operator.
     */
    public void setChild(Operator child) {
        this.child = child;
    }

    /**
     * Register this operator's schema transformation.
     * @see com.github.jinba1.blazedb.PlanContext the per-query registry this updates.
     */
    protected abstract void registerSchema() ;
    /**
     * Check if this operator has registered its schema.
     */
    public final void ensureSchemaRegistered() {
        if (!schemaRegistered) {
            registerSchema();
        }
    }

    /**
     * Recursively update the Schema from bottom up for the query plan.
     * To match the changes in query plan after optimisations like projection push down.
     * @see com.github.jinba1.blazedb.QueryPlanOptimizer used for query optimisations.
     */
    public void updateSchema() {
        // Base implementation: re-register schema and propagate to child

        // Propagate to child if exists
        if (this.hasChild()) {
            this.child.updateSchema();
        }

        this.schemaRegistered = false;

        registerSchema();
    }

    /**
     * Resolves column references to their corresponding indices in the schema.
     * This utility method takes a list of columns and resolves each column to its
     * index position in the specified schema. It can either create a new list of indices
     * or populate an existing list with the resolved indices.
     * @param columns The list of column references to resolve
     * @param schemaId The schema identifier to resolve against
     * @param targetList Optional existing list to populate with resolved indices (will be cleared if not null)
     * @return A list of resolved column indices, either the provided targetList or a new ArrayList
     * @throws RuntimeException If any column cannot be resolved in the specified schema
     */
    protected List<Integer> resolveColumnIndices(List<Column> columns, String schemaId,
                                                 List<Integer> targetList) {
        List<Integer> indices = targetList != null ? targetList : new ArrayList<>();
        if (targetList != null) {
            targetList.clear();
        }

        for (Column column : columns) {
            String tableName = column.getTable().getName();
            String columnName = column.getColumnName();

            Integer index = ctx.resolveColumnWithOrigins(schemaId, tableName, columnName);
            if (index == null) {
                throw new RuntimeException("Column " + tableName + "." + columnName +
                        " not found in schema " + schemaId);
            }

            indices.add(index);
        }

        return indices;
    }

    /**
     * Helper method for operations that preserve schema structure but add details.
     * @param child                 The child operator
     * @param transformationDetails The details of the transformation
     * @return The ID of the registered schema
     */
    protected String registerPassthroughSchema(
            Operator child,
            Map<String, String> transformationDetails) {

        String childSchemaId = child.propagateSchemaId();
        Map<String, Integer> childSchema = ctx.getSchema(childSchemaId);
        if (childSchema == null) {
            throw new QueryExecutionException(
                    "No schema found for id '" + childSchemaId + "'");
        }

        // Create identical schema structure
        Map<String, Integer> newSchema = new HashMap<>(childSchema);

        // Register with transformation details
        return ctx.registerSchemaWithTransformation(
                newSchema,
                childSchemaId,
                SchemaTransformationType.OTHER,
                transformationDetails
        );
    }
}