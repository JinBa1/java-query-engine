package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.ExpressionEvaluator;
import com.github.jinba1.blazedb.QueryExecutionException;
import com.github.jinba1.blazedb.Tuple;
import com.github.jinba1.blazedb.Value;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The HashJoinOperator implements an in-memory hash join. The inner (build) side is
 * drained once into a hash table keyed by the equality conjuncts of the join condition;
 * the outer (probe) side then streams through once. Candidate matches re-evaluate the
 * full original condition, so residual non-equality conjuncts behave exactly as in the
 * nested-loop join — and the output order (outer-major, inner order within key) is
 * identical to {@link JoinOperator}'s.
 *
 * <p>Equi-key column indices are derived lazily at the first getNextTuple() call and
 * invalidated when children or schemas change, because the optimizer rewires join
 * children after construction.
 */
public class HashJoinOperator extends JoinOperator {

    // Lazily derived equi-key column indices (parallel lists: outer side / inner side)
    private List<Integer> outerKeyIndices;
    private List<Integer> innerKeyIndices;

    // Build state
    private Map<List<Value>, List<Tuple>> buildTable;
    private boolean built = false;
    private boolean keyTypesChecked = false;

    // Probe state
    private Tuple currentOuterTuple;
    private Iterator<Tuple> currentBucket;
    private ExpressionEvaluator probeEvaluator;

    /**
     * Constructs a hash join over an outer (probe) and inner (build) child.
     * @param outerChild The outer (probe) child operator, streamed once
     * @param innerChild The inner (build) child operator, drained once into the hash table
     * @param expression The join condition; must contain at least one column-to-column
     *                   equality conjunct by the time execution starts
     */
    public HashJoinOperator(Operator outerChild, Operator innerChild, Expression expression) {
        super(outerChild, innerChild, expression);
    }

    @Override
    public Tuple getNextTuple() {
        if (!built) {
            prepare();
        }
        while (true) {
            if (currentBucket != null) {
                while (currentBucket.hasNext()) {
                    Tuple combined = combineTuples(currentOuterTuple, currentBucket.next());
                    if (probeEvaluator.evaluate(getJoinCondition(), combined)) {
                        countTuple();
                        return combined;
                    }
                }
                currentBucket = null;
            }
            currentOuterTuple = getOuterChild().getNextTuple();
            if (currentOuterTuple == null) {
                return null;
            }
            List<Value> key = extractKey(currentOuterTuple, outerKeyIndices);
            checkKeyTypes(key);
            List<Tuple> bucket = buildTable.get(key);
            currentBucket = bucket == null ? Collections.emptyIterator() : bucket.iterator();
        }
    }

    /** Derives keys, builds the evaluator, and drains the inner child into the hash table. */
    private void prepare() {
        deriveKeys();
        probeEvaluator = new ExpressionEvaluator(propagateSchemaId());
        buildTable = new HashMap<>();
        Tuple tuple;
        while ((tuple = getChild().getNextTuple()) != null) {
            buildTable.computeIfAbsent(extractKey(tuple, innerKeyIndices), k -> new ArrayList<>())
                    .add(tuple);
        }
        built = true;
    }

    /**
     * Resolves the cross-side equality conjuncts into key column indices.
     * Same-side equalities (rare) fall through to the residual full-condition check.
     */
    private void deriveKeys() {
        outerKeyIndices = new ArrayList<>();
        innerKeyIndices = new ArrayList<>();
        String outerSchemaId = getOuterChild().propagateSchemaId();
        String innerSchemaId = getChild().propagateSchemaId();
        DBCatalog catalog = DBCatalog.getInstance();

        for (Expression conjunct : flattenConjuncts(getJoinCondition())) {
            if (!(conjunct instanceof EqualsTo equalsTo)
                    || !(equalsTo.getLeftExpression() instanceof Column left)
                    || !(equalsTo.getRightExpression() instanceof Column right)) {
                continue;
            }
            Integer leftOuter = resolve(catalog, outerSchemaId, left);
            Integer rightInner = resolve(catalog, innerSchemaId, right);
            if (leftOuter != null && rightInner != null) {
                outerKeyIndices.add(leftOuter);
                innerKeyIndices.add(rightInner);
                continue;
            }
            Integer leftInner = resolve(catalog, innerSchemaId, left);
            Integer rightOuter = resolve(catalog, outerSchemaId, right);
            if (rightOuter != null && leftInner != null) {
                outerKeyIndices.add(rightOuter);
                innerKeyIndices.add(leftInner);
            }
        }

        if (outerKeyIndices.isEmpty()) {
            throw new QueryExecutionException(
                    "Hash join selected for condition without a cross-side equality: '"
                            + getJoinCondition() + "'");
        }
    }

    private static Integer resolve(DBCatalog catalog, String schemaId, Column column) {
        String table = column.getTable() != null ? column.getTable().getName() : null;
        return catalog.resolveColumnWithOrigins(schemaId, table, column.getColumnName());
    }

    private static List<Value> extractKey(Tuple tuple, List<Integer> indices) {
        List<Value> key = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            key.add(tuple.getAttribute(index));
        }
        return key;
    }

    /**
     * Cross-type join keys never collide in the hash table, which would silently return
     * an empty result where the nested-loop join throws. Check once and be equally loud.
     */
    private void checkKeyTypes(List<Value> outerKey) {
        if (keyTypesChecked || buildTable.isEmpty()) {
            return;
        }
        List<Value> sample = buildTable.keySet().iterator().next();
        for (int i = 0; i < outerKey.size(); i++) {
            if (!outerKey.get(i).getClass().equals(sample.get(i).getClass())) {
                throw new QueryExecutionException(
                        "Type mismatch in join key: cannot compare " + outerKey.get(i).typeName()
                                + " with " + sample.get(i).typeName()
                                + " in '" + getJoinCondition() + "'");
            }
        }
        keyTypesChecked = true;
    }

    /** Flattens an AND tree into its conjuncts (a non-AND expression is one conjunct). */
    static List<Expression> flattenConjuncts(Expression expression) {
        List<Expression> conjuncts = new ArrayList<>();
        collectConjuncts(expression, conjuncts);
        return conjuncts;
    }

    private static void collectConjuncts(Expression expression, List<Expression> out) {
        if (expression instanceof AndExpression and) {
            collectConjuncts(and.getLeftExpression(), out);
            collectConjuncts(and.getRightExpression(), out);
        } else if (expression != null) {
            out.add(expression);
        }
    }

    /** True when the condition contains at least one column-to-column equality conjunct. */
    public static boolean hasEquiConjunct(Expression expression) {
        for (Expression conjunct : flattenConjuncts(expression)) {
            if (conjunct instanceof EqualsTo equalsTo
                    && equalsTo.getLeftExpression() instanceof Column
                    && equalsTo.getRightExpression() instanceof Column) {
                return true;
            }
        }
        return false;
    }

    /** Drops all derived/built state; next getNextTuple() re-derives and rebuilds. */
    private void invalidate() {
        built = false;
        keyTypesChecked = false;
        buildTable = null;
        outerKeyIndices = null;
        innerKeyIndices = null;
        probeEvaluator = null;
        currentOuterTuple = null;
        currentBucket = null;
    }

    @Override
    public void reset() {
        super.reset(); // resets both children
        built = false;
        keyTypesChecked = false;
        buildTable = null;
        currentOuterTuple = null;
        currentBucket = null;
        // key indices are re-derived by prepare() on the next getNextTuple()
    }

    @Override
    public void setChild(Operator innerChild) {
        super.setChild(innerChild);
        invalidate();
    }

    @Override
    public void setOuterChild(Operator outerChild) {
        super.setOuterChild(outerChild);
        invalidate();
    }

    @Override
    public void updateSchema() {
        super.updateSchema();
        invalidate();
    }

    @Override
    public String describe() {
        return "HashJoin[" + getJoinCondition() + "]";
    }
}
