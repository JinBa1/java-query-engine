package com.github.jinba1.blazedb;

import java.util.Comparator;
import java.util.List;

/**
 * A custom comparator class for the tuples.
 * @see com.github.jinba1.blazedb.operator.SortOperator
 * Used to sort tuples based on specified sort columns.
 * Assume only ascending order.
 */
public class TupleComparator implements Comparator<Tuple> {

    // The column indices for the attributes to sort tuple with
    private List<Integer> sortColumnIndices;

    /**
     * Construct a tuple comparator given an ordered list of columns to sort with.
     * @param sortColumnIndices An ordered list containing columns to sort the tuple with
     *                          The order is specified by the ORDER BY from query.
     */
    public TupleComparator(List<Integer> sortColumnIndices) {
        this.sortColumnIndices = sortColumnIndices;
    }

    /**
     * Compare tuples attribute by attribute, based on the column indices in order.
     * For each sort column, compare the attribute values until a difference is found.
     * @param t1 The first tuple to compare.
     * @param t2 The second tuple to compare.
     * @return A negative integer if t1 comes before t2, a positive integer if t1 comes after t2,
     *         or 0 if they are equal according to the sort columns. In particular:
     *         - A negative value if the first differing attribute in t1 is less than in t2.
     *         - A positive value if the first differing attribute in t1 is greater than in t2.
     *         - Zero if all sort attributes are equal between t1 and t2.
     */
    @Override
    public int compare(Tuple t1, Tuple t2) {
        for (Integer columnIndex : sortColumnIndices) {
            int value1 = t1.getAttribute(columnIndex);
            int value2 = t2.getAttribute(columnIndex);

            if (value1 != value2) {
                return value1 - value2; // Ascending order
            }
        }
        return 0; // Tuples are equal
    }
}
