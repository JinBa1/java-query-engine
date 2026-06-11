package com.github.jinba1.blazedb;

import java.util.List;
import java.util.Objects;

/**
 * The Tuple class represents a row of data.
 * A tuple consists of an ordered list of integer attribute values.
 * The tuple attributes are immutable after creation.
 * The tuple itself is unaware of the schema despite maintaining order.
 */
public class Tuple {

    // Attribute values in a row
    private final List<Value> attributes;

    /**
     * Construct a tuple consisting a list of ordered attributes.
     * @param attributes A list of typed values representing the attribute values.
     */
    public Tuple(List<Value> attributes) {
        this.attributes = attributes;
    }

    /**
     * Get the list of attributes.
     * @return An ordered list of typed attributes.
     */
    public List<Value> getTuple() {
        return attributes;
    }

    /**
     * Get a single attribute.
     * @param i The zero-based index of the attribute to retrieve.
     * @return The typed value of the single attribute.
     */
    public Value getAttribute(int i) {
        return attributes.get(i);
    }

    /**
     * Converts the tuple to a comma-separated string.
     * @see BlazeDB used for writing tuples to output file.
     * @return A string representation of the row of attributes.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attributes.size(); i++) {
            sb.append(attributes.get(i));
            if (i < attributes.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
    /**
     * Two tuples are equal if they have the same number of attributes
     * and each attribute at the same position has the same value.
     * @see ExpressionPreprocessor used for separating join conditions from select.
     * @see QueryPlanOptimizer used for projection pushdown.
     * @param o The object to compare with.
     * @return true if given object is a tuple with identical attributes in the same order
     *         false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tuple other = (Tuple) o;

        // Two tuples are equal if they have the same attributes
        if (attributes.size() != other.attributes.size()) {
            return false;
        }

        for (int i = 0; i < attributes.size(); i++) {
            if (!Objects.equals(attributes.get(i), other.attributes.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Return a hash code for this tuple.
     * The hash code is computed based on attribute values
     * so that equal tuples have equal hash codes.
     * @see com.github.jinba1.blazedb.operator.DuplicateEliminationOperator used by hash-based distinct, etc
     * @return A hash code value for this tuple.
     */
    @Override
    public int hashCode() {
        return Objects.hash(attributes);
    }
}