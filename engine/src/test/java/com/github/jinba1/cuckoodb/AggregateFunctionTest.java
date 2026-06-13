package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AggregateFunctionTest {

    @Test
    public void recognizesAllFiveFunctionsCaseInsensitively() {
        assertEquals(AggregateFunction.SUM, AggregateFunction.fromFunctionName("SUM"));
        assertEquals(AggregateFunction.SUM, AggregateFunction.fromFunctionName("sum"));
        assertEquals(AggregateFunction.COUNT, AggregateFunction.fromFunctionName("Count"));
        assertEquals(AggregateFunction.AVG, AggregateFunction.fromFunctionName("avg"));
        assertEquals(AggregateFunction.MIN, AggregateFunction.fromFunctionName("MIN"));
        assertEquals(AggregateFunction.MAX, AggregateFunction.fromFunctionName("max"));
    }

    @Test
    public void rejectsNonAggregateNames() {
        assertNull(AggregateFunction.fromFunctionName("CONCAT"));
        assertNull(AggregateFunction.fromFunctionName(""));
        assertNull(AggregateFunction.fromFunctionName(null));
    }
}
