package com.github.jinba1.blazedb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ValueTest {

    @Test
    public void intValuesCompareNumerically() {
        assertTrue(new IntValue(2).compareTo(new IntValue(10)) < 0);
        assertEquals(0, new IntValue(5).compareTo(new IntValue(5)));
    }

    @Test
    public void stringValuesCompareLexicographically() {
        assertTrue(new StringValue("apple").compareTo(new StringValue("banana")) < 0);
        // lexicographic, not numeric: "10" < "2"
        assertTrue(new StringValue("10").compareTo(new StringValue("2")) < 0);
    }

    @Test
    public void crossTypeComparisonThrows() {
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> new IntValue(1).compareTo(new StringValue("1")));
        assertTrue(e.getMessage().contains("int"));
        assertTrue(e.getMessage().contains("string"));
    }

    @Test
    public void recordEqualityAndHashing() {
        assertEquals(new IntValue(7), new IntValue(7));
        assertEquals(new StringValue("x"), new StringValue("x"));
        assertNotEquals(new IntValue(7), new StringValue("7"));
        assertEquals(new IntValue(7).hashCode(), new IntValue(7).hashCode());
    }

    @Test
    public void toStringIsRaw() {
        assertEquals("42", new IntValue(42).toString());
        assertEquals("a,b", new StringValue("a,b").toString()); // no quoting here; CSV printer quotes
    }

    @Test
    public void typeNameReportsKind() {
        assertEquals("int", new IntValue(1).typeName());
        assertEquals("string", new StringValue("s").typeName());
    }
}
