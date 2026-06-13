package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.AggregateCall;
import com.github.jinba1.blazedb.ErrorCode;
import com.github.jinba1.blazedb.IntValue;
import com.github.jinba1.blazedb.QueryExecutionException;
import com.github.jinba1.blazedb.Value;

/**
 * Per-group, per-call aggregate accumulator: one instance folds the values of a single
 * aggregate call within a single group.
 *
 * <p>{@code add} receives the evaluated argument value for each input row — null only for
 * COUNT(*), which counts rows without evaluating an argument. {@code result} produces the
 * finalized value. An accumulator only exists for groups with at least one row, so
 * {@code result} never runs on empty state.
 */
interface Accumulator {

    /** Folds one row's evaluated argument into the running aggregate (null only for COUNT(*)). */
    void add(Value value);

    /** Returns the finalized aggregate value. */
    Value result();

    /** Creates the accumulator implementation for an aggregate call. */
    static Accumulator create(AggregateCall call) {
        return switch (call.function()) {
            case SUM -> new IntSumAccumulator(call.schemaKey(), false);
            case AVG -> new IntSumAccumulator(call.schemaKey(), true);
            case COUNT -> new CountAccumulator(call.schemaKey());
            case MIN -> new MinMaxAccumulator(-1);
            case MAX -> new MinMaxAccumulator(1);
        };
    }

    /**
     * SUM and AVG: accumulates in long to survive intermediate overflow; the final value is
     * range-checked back into int. AVG truncates toward zero (Java integer division),
     * e.g. AVG(3,4) = 3 and AVG(-3,-4) = -3.
     */
    class IntSumAccumulator implements Accumulator {
        private final String schemaKey;
        private final boolean average;
        private long sum = 0;
        private long count = 0;

        IntSumAccumulator(String schemaKey, boolean average) {
            this.schemaKey = schemaKey;
            this.average = average;
        }

        @Override
        public void add(Value value) {
            if (!(value instanceof IntValue iv)) {
                throw new QueryExecutionException(ErrorCode.TYPE_MISMATCH,
                        (average ? "AVG" : "SUM") + " requires int values; got " + value.typeName()
                                + " value '" + value + "' in '" + schemaKey + "'");
            }
            sum += iv.v();
            count++;
        }

        @Override
        public Value result() {
            long raw = average ? sum / count : sum;
            if (raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
                throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                        (average ? "AVG" : "SUM") + " overflow: " + raw
                                + " exceeds int range in '" + schemaKey + "'");
            }
            return new IntValue((int) raw);
        }
    }

    /**
     * COUNT and COUNT(*): counts rows. The engine has no NULLs, so COUNT(col) and COUNT(*)
     * are always equal; the argument value is ignored here (it was evaluated upstream, which
     * validates the column exists).
     */
    class CountAccumulator implements Accumulator {
        private final String schemaKey;
        private long count = 0;

        CountAccumulator(String schemaKey) {
            this.schemaKey = schemaKey;
        }

        @Override
        public void add(Value value) {
            count++;
        }

        @Override
        public Value result() {
            if (count > Integer.MAX_VALUE) {
                throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                        "COUNT overflow: " + count + " exceeds int range in '" + schemaKey + "'");
            }
            return new IntValue((int) count);
        }
    }

    /**
     * MIN/MAX over any value type; comparison delegates to {@link Value#compareTo}, so strings
     * compare lexicographically. Column values within a group are type-uniform, so cross-type
     * comparison is unreachable here for column arguments.
     */
    class MinMaxAccumulator implements Accumulator {
        private final int sign; // -1 keeps the smaller value (MIN), +1 keeps the larger (MAX)
        private Value best = null;

        MinMaxAccumulator(int sign) {
            this.sign = sign;
        }

        @Override
        public void add(Value value) {
            if (best == null || Integer.signum(value.compareTo(best)) == sign) {
                best = value;
            }
        }

        @Override
        public Value result() {
            return best;
        }
    }
}
