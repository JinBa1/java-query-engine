package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.ExpressionEvaluator;
import com.github.jinba1.blazedb.Tuple;
import net.sf.jsqlparser.expression.Expression;

import java.util.HashMap;
import java.util.Map;
/**
 * This operator filters tuples received from its child operator based on a selection predicate,
 * represented as a {@link net.sf.jsqlparser.expression.Expression}. It returns only those tuples
 * that satisfy the condition.
 * <p>The selection condition is evaluated using an {@link ExpressionEvaluator}.
 * @see ExpressionEvaluator
 * @see Operator
 */
public class SelectOperator extends Operator {

    private Expression expression;
    private ExpressionEvaluator evaluator;

    /**
     * Construct a select operator with a given child operator and selection condition.
     * @param child The child Operator whose output are this operator's input.
     * @param expression The JsqlParser expression representing the select conditions.
     */
    public SelectOperator(Operator child, Expression expression) {
        this.child = child;
        this.expression = expression;

        this.child.ensureSchemaRegistered();

        registerSchema();

        this.evaluator = new ExpressionEvaluator(intermediateSchemaId);
    }

    /**
     * Retrieves the next tuple from the child operator that satisfies the selection condition.
     * The method skips over tuples that do not meet the criteria and returns the first valid tuple found.
     * @return The next tuple satisifying the condition, or null if no such tuple exist anymore.
     */
    @Override
    public Tuple getNextTuple() {

        while (true) {
            Tuple nextTuple = child.getNextTuple();
            if (nextTuple == null) {
                break; //reached end of table because returned null
            }
            if (evaluator.evaluate(expression, nextTuple)) {
                countTuple();
                return nextTuple; //expression hold, return this tuple
            }
        }
        return null; //should only invoke when reaching end of table
    }

    /**
     * Reset the operator and its child to allow re-scanning.
     * The evaluator state is preserved as the condition remains unchanged.
     */
    @Override
    public void reset() {
        child.reset();
    }
    /**
     * Returns the schema identifier propagated from the child operator, since
     * selection does not alter the schema.
     * @return Unique identifier for the schema.
     */
    @Override
    public String propagateSchemaId() {
        ensureSchemaRegistered();
        return intermediateSchemaId;
    }

    /**
     * Get the select conditions.
     * @return The selection condition as expression instance.
     */
    public Expression getCondition() {
        return expression;
    }

    /**
     * Register the selection condition of this operator.
     * The select operator does not alter schema, but the selection conditon is
     * relevant to decisions during selection push down.
     * This method provides the optimizer with necessary information.
     */
    @Override
    protected void registerSchema() {
        if (schemaRegistered) return;

        // Create details about the conditions
        Map<String, String> transformationDetails = new HashMap<>();
        transformationDetails.put("condition", expression.toString());

        intermediateSchemaId = registerPassthroughSchema(
                child,
                transformationDetails
        );

        schemaRegistered = true;
    }

    /**
     * Recursively update the schema from bottom up.
     * For select operator, some optimisations may cause the column references
     * to change and the selection condition needs to match these changes.
     * This is necessary due to how column references are resolved.
     */
    @Override
    public void updateSchema() {
        super.updateSchema();

        // CRITICAL FIX: Recreate evaluator with updated schema ID
        this.evaluator = new ExpressionEvaluator(intermediateSchemaId);
    }
}
