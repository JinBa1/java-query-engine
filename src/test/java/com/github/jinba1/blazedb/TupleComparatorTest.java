package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TupleComparatorTest {

    private TupleComparator comparator;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testSingleColumnAscending() {
        comparator = new TupleComparator(Collections.singletonList(0));

        Tuple small = new Tuple(TestTuples.ints(1, 10));
        Tuple medium = new Tuple(TestTuples.ints(5, 20));
        Tuple large = new Tuple(TestTuples.ints(10, 30));

        assertTrue(comparator.compare(small, medium) < 0, "1 < 5 → negative");
        assertTrue(comparator.compare(large, medium) > 0, "10 > 5 → positive");
        assertEquals(0, comparator.compare(small, small), "equal tuples → 0");
    }

    @Test
    void testSingleColumnSortOrder() {
        comparator = new TupleComparator(Collections.singletonList(0));

        Tuple t1 = new Tuple(TestTuples.ints(3, 0));
        Tuple t2 = new Tuple(TestTuples.ints(1, 0));
        Tuple t3 = new Tuple(TestTuples.ints(2, 0));

        List<Tuple> tuples = Arrays.asList(t1, t2, t3);
        tuples.sort(comparator);

        assertEquals(Arrays.asList(1, 2, 3).toString(),
                Arrays.asList(tuples.get(0).getAttribute(0), tuples.get(1).getAttribute(0), tuples.get(2).getAttribute(0)).toString(),
                "Sorted order should be [1, 2, 3]");
    }

    @Test
    void testMultiColumnSort() {
        // Primary key: column 0, Secondary key: column 1
        comparator = new TupleComparator(Arrays.asList(0, 1));

        // All share same primary key (5), differentiated by secondary
        Tuple t1 = new Tuple(TestTuples.ints(5, 30));
        Tuple t2 = new Tuple(TestTuples.ints(5, 10));
        Tuple t3 = new Tuple(TestTuples.ints(5, 20));

        List<Tuple> tuples = Arrays.asList(t1, t2, t3);
        tuples.sort(comparator);

        assertEquals(new IntValue(10), tuples.get(0).getAttribute(1), "Secondary: 10 first");
        assertEquals(new IntValue(20), tuples.get(1).getAttribute(1), "Secondary: 20 second");
        assertEquals(new IntValue(30), tuples.get(2).getAttribute(1), "Secondary: 30 third");
    }

    @Test
    void testMultiColumnPrimaryBreaksTie() {
        // Primary key: column 0, Secondary key: column 1
        comparator = new TupleComparator(Arrays.asList(0, 1));

        Tuple t1 = new Tuple(TestTuples.ints(1, 100));
        Tuple t2 = new Tuple(TestTuples.ints(2, 1));

        // Primary keys differ (1 vs 2), secondary ignored
        assertTrue(comparator.compare(t1, t2) < 0, "Primary key breaks tie");
    }

    @Test
    void testEqualTuplesReturnZero() {
        comparator = new TupleComparator(Arrays.asList(0, 1, 2));

        Tuple t1 = new Tuple(TestTuples.ints(7, 42, 99));
        Tuple t2 = new Tuple(TestTuples.ints(7, 42, 99));

        assertEquals(0, comparator.compare(t1, t2), "Identical tuples → 0");
    }

    @Test
    void testEqualOnSortColumnsOnly() {
        // Only comparing on column 0, even if other columns differ
        comparator = new TupleComparator(Collections.singletonList(0));

        Tuple t1 = new Tuple(TestTuples.ints(5, 100));
        Tuple t2 = new Tuple(TestTuples.ints(5, 999));

        assertEquals(0, comparator.compare(t1, t2), "Equal on sort column → 0, regardless of other values");
    }

    @Test
    void testDifferentTupleLengths() {
        comparator = new TupleComparator(Collections.singletonList(0));

        Tuple shortTuple = new Tuple(TestTuples.ints(3));
        Tuple longTuple = new Tuple(TestTuples.ints(3, 10, 20, 30));

        // Both have value 3 at index 0
        assertEquals(0, comparator.compare(shortTuple, longTuple), "Same value at index 0 → 0 despite different lengths");
    }

    @Test
    void testDifferentTupleLengthsDifferentValues() {
        comparator = new TupleComparator(Arrays.asList(0, 1));

        Tuple t1 = new Tuple(TestTuples.ints(1, 5));
        Tuple t2 = new Tuple(TestTuples.ints(1, 5, 99, 100));

        // Equal on all sort columns (0 and 1)
        assertEquals(0, comparator.compare(t1, t2), "Equal on compared columns → 0");
    }

    @Test
    void testNegativeValues() {
        comparator = new TupleComparator(Collections.singletonList(0));

        Tuple negative = new Tuple(TestTuples.ints(-5));
        Tuple zero = new Tuple(TestTuples.ints(0));
        Tuple positive = new Tuple(TestTuples.ints(10));

        assertTrue(comparator.compare(negative, zero) < 0, "-5 < 0");
        assertTrue(comparator.compare(zero, positive) < 0, "0 < 10");
        assertTrue(comparator.compare(negative, positive) < 0, "-5 < 10");
    }

    @Test
    void testThreeColumnSort() {
        comparator = new TupleComparator(Arrays.asList(0, 1, 2));

        Tuple t1 = new Tuple(TestTuples.ints(1, 2, 3));
        Tuple t2 = new Tuple(TestTuples.ints(1, 2, 1));
        Tuple t3 = new Tuple(TestTuples.ints(1, 1, 99));
        Tuple t4 = new Tuple(TestTuples.ints(2, 0, 0));

        List<Tuple> tuples = Arrays.asList(t1, t2, t3, t4);
        tuples.sort(comparator);

        assertEquals(new IntValue(1), tuples.get(0).getAttribute(0));
        assertEquals(new IntValue(1), tuples.get(0).getAttribute(1));
        assertEquals(new IntValue(99), tuples.get(0).getAttribute(2));

        assertEquals(new IntValue(1), tuples.get(1).getAttribute(0));
        assertEquals(new IntValue(2), tuples.get(1).getAttribute(1));
        assertEquals(new IntValue(1), tuples.get(1).getAttribute(2));

        assertEquals(new IntValue(1), tuples.get(2).getAttribute(0));
        assertEquals(new IntValue(2), tuples.get(2).getAttribute(1));
        assertEquals(new IntValue(3), tuples.get(2).getAttribute(2));

        assertEquals(new IntValue(2), tuples.get(3).getAttribute(0));
        assertEquals(new IntValue(0), tuples.get(3).getAttribute(1));
        assertEquals(new IntValue(0), tuples.get(3).getAttribute(2));
    }
}
