package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.Tuple;

import java.util.HashMap;
import java.util.Map;

/**
 * The LimitOperator implements the LIMIT clause in SQL.
 * It emits at most {@code limit} tuples from its child, then reports EOF.
 * The planner places it at the top of the plan, above DISTINCT and ORDER BY,
 * so it caps the final result.
 */
public class LimitOperator extends Operator {

    private final long limit;
    private long emitted = 0;

    /**
     * Constructs a LimitOperator capping the child's output.
     * @param child The child operator from which to read tuples
     * @param limit The maximum number of tuples to emit (non-negative)
     */
    public LimitOperator(PlanContext ctx, Operator child, long limit) {
        super(ctx);
        this.child = child;
        this.limit = limit;
        this.child.ensureSchemaRegistered();
        registerSchema();
    }

    @Override
    public Tuple getNextTuple() {
        if (emitted >= limit) {
            return null;
        }
        Tuple tuple = child.getNextTuple();
        if (tuple != null) {
            emitted++;
            countTuple();
        }
        return tuple;
    }

    @Override
    public void reset() {
        child.reset();
        emitted = 0;
    }

    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    @Override
    public String describe() {
        return "Limit[" + limit + "]";
    }

    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;
        Map<String, String> transformationDetails = new HashMap<>();
        transformationDetails.put("limit", String.valueOf(limit));
        intermediateSchemaId = registerPassthroughSchema(child, transformationDetails);
        schemaRegistered = true;
    }
}
