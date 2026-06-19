package com.github.jinba1.cuckoodb;

import org.apache.commons.csv.CSVFormat;

/**
 * The single CSV dialect every table reader must use, so the catalog's parse-and-infer, the
 * {@code ScanOperator}'s row scan, and any external row count all tokenize a file identically.
 * RFC4180 with surrounding spaces ignored — keeping these in one constant prevents the readers
 * from drifting (e.g. a leading space before a quoted, embedded-newline field changes the record
 * count between {@code ignoreSurroundingSpaces} and plain RFC4180). {@link CSVFormat} is immutable
 * and thread-safe, so this shared instance is safe to reuse across threads and parsers.
 */
public final class CsvFormats {

    private CsvFormats() {
    }

    /** The dialect for every base-table CSV: RFC4180, surrounding spaces ignored. */
    public static final CSVFormat TABLE = CSVFormat.RFC4180.builder()
            .setIgnoreSurroundingSpaces(true)
            .build();
}
