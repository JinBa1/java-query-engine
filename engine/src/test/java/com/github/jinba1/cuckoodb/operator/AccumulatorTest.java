package com.github.jinba1.cuckoodb.operator;

import com.github.jinba1.cuckoodb.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AccumulatorTest {

    private static AggregateCall call(AggregateFunction fn, String schemaKey) {
        // argument expression is not used by accumulators; null is fine here
        return new AggregateCall(fn, null, schemaKey);
    }

    private static Accumulator of(AggregateFunction fn) {
        return Accumulator.create(call(fn, fn.name() + "(t.c)"));
    }

    @Test
    public void sumAddsInts() {
        Accumulator acc = of(AggregateFunction.SUM);
        acc.add(new IntValue(10));
        acc.add(new IntValue(-3));
        assertEquals(new IntValue(7), acc.result());
    }

    @Test
    public void sumRejectsStringsWithClearError() {
        Accumulator acc = of(AggregateFunction.SUM);
        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> acc.add(new StringValue("abc")));
        assertTrue(ex.getMessage().contains("SUM requires int"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SUM(t.c)"), ex.getMessage());
    }

    @Test
    public void sumOverflowPastIntRangeThrows() {
        Accumulator acc = of(AggregateFunction.SUM);
        acc.add(new IntValue(Integer.MAX_VALUE));
        acc.add(new IntValue(1));
        QueryExecutionException ex = assertThrows(QueryExecutionException.class, acc::result);
        assertTrue(ex.getMessage().contains("overflow"), ex.getMessage());
    }

    @Test
    public void avgTruncatesTowardZero() {
        Accumulator acc = of(AggregateFunction.AVG);
        acc.add(new IntValue(3));
        acc.add(new IntValue(4));
        assertEquals(new IntValue(3), acc.result()); // 7/2 -> 3

        Accumulator neg = of(AggregateFunction.AVG);
        neg.add(new IntValue(-3));
        neg.add(new IntValue(-4));
        assertEquals(new IntValue(-3), neg.result()); // -7/2 -> -3, not -4
    }

    @Test
    public void avgRejectsStrings() {
        Accumulator acc = of(AggregateFunction.AVG);
        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> acc.add(new StringValue("x")));
        assertTrue(ex.getMessage().contains("AVG requires int"), ex.getMessage());
    }

    @Test
    public void countCountsRowsIgnoringValues() {
        Accumulator acc = of(AggregateFunction.COUNT);
        acc.add(null);                      // COUNT(*) row
        acc.add(new IntValue(5));           // COUNT(col) row
        acc.add(new StringValue("alice"));  // COUNT on a string column is fine
        assertEquals(new IntValue(3), acc.result());
    }

    @Test
    public void minMaxOnInts() {
        Accumulator min = of(AggregateFunction.MIN);
        Accumulator max = of(AggregateFunction.MAX);
        for (int v : new int[]{5, -2, 9}) {
            min.add(new IntValue(v));
            max.add(new IntValue(v));
        }
        assertEquals(new IntValue(-2), min.result());
        assertEquals(new IntValue(9), max.result());
    }

    @Test
    public void minMaxOnStringsIsLexicographic() {
        Accumulator min = of(AggregateFunction.MIN);
        Accumulator max = of(AggregateFunction.MAX);
        for (String s : new String[]{"carol", "alice", "bob"}) {
            min.add(new StringValue(s));
            max.add(new StringValue(s));
        }
        assertEquals(new StringValue("alice"), min.result());
        assertEquals(new StringValue("carol"), max.result());
    }
}
