package com.github.jinba1.cuckoodb;

/**
 * Which limit of a {@link QueryBudget} was breached. The engine throws one
 * {@link QueryBudgetExceededException} type for both, but downstream surfaces treat them
 * differently: a network gateway maps a {@link #TUPLES} breach to "retry with a narrower
 * scope" (429) and a {@link #TIME} breach to "the query took too long" (504). Carried as a
 * field so consumers classify by enum, never by parsing the message.
 */
public enum BudgetKind {
    /** The tuple-count limit was exceeded. */
    TUPLES,
    /** The wall-clock time limit was exceeded. */
    TIME
}
