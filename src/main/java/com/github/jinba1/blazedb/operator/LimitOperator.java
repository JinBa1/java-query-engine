package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.QueryBudgetExceededException;
import com.github.jinba1.blazedb.Tuple;

import java.util.HashMap;
import java.util.Map;

/**
 * The LimitOperator implements the LIMIT clause in SQL.
 * It emits at most {@code limit} tuples from its child, then reports EOF.
 * The planner places it at the top of the plan, above DISTINCT and ORDER BY,
 * so it caps the final result.
 *
 * <p>When the cap is hit, the operator peeks the child once more so
 * {@link #wasTruncated()} can tell a truncated result from one with exactly
 * {@code limit} rows — agent callers decide whether to refine based on it.
 */
public class LimitOperator extends Operator {

    private final long limit;
    private long emitted = 0;
    private boolean peeked = false;
    private boolean truncated = false;

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
            peekForMore();
            return null;
        }
        Tuple tuple = child.getNextTuple();
        if (tuple != null) {
            emitted++;
            countTuple();
        }
        return tuple;
    }

    /** Pulls the child once past the cap; a row there means the result was truncated. */
    private void peekForMore() {
        if (peeked) {
            return;
        }
        peeked = true;
        try {
            truncated = child.getNextTuple() != null;
        } catch (QueryBudgetExceededException e) {
            // The capped result is already complete; killing the query (and deleting
            // its output) over this metadata probe would be absurd. Budget exhaustion
            // while probing for more rows honestly reads as "result may be incomplete".
            truncated = true;
        }
    }

    /**
     * Whether the child had more rows than the cap. Only meaningful after the drain
     * completed (this operator returned null); false before that.
     */
    public boolean wasTruncated() {
        return truncated;
    }

    @Override
    public void reset() {
        child.reset();
        emitted = 0;
        peeked = false;
        truncated = false;
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
