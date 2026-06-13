package com.github.jinba1.cuckoodb;

/**
 * The aggregate functions supported in SELECT lists.
 */
public enum AggregateFunction {
    SUM, COUNT, AVG, MIN, MAX;

    /**
     * Maps a SQL function name to an aggregate function.
     * @param name The function name as written in the query (any case)
     * @return The matching aggregate function, or null if the name is not an aggregate
     */
    public static AggregateFunction fromFunctionName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
