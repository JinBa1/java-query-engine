package com.github.jinba1.cuckoodb;

/**
 * Execution metadata for one completed query: rows written, whether a LIMIT cut the
 * result short, and a refinement hint for agent callers when it did. The output file
 * itself stays pure data; this record travels back to the caller (CLI today, the
 * REST gateway later).
 *
 * @param rows      number of data rows written (excluding the header)
 * @param truncated true when a LIMIT stopped the query although more rows existed
 * @param hint      how to refine the query when truncated; null otherwise
 */
public record QueryResult(long rows, boolean truncated, String hint) {

    static QueryResult complete(long rows) {
        return new QueryResult(rows, false, null);
    }

    static QueryResult truncated(long rows) {
        return new QueryResult(rows, true,
                "Result truncated at " + rows + " rows; more rows exist. "
                        + "Narrow with a WHERE filter or raise LIMIT.");
    }
}
