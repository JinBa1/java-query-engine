package com.github.jinba1.cuckoodb.server.web;

/**
 * The process-wide registered-table cap is reached and there is no eviction. Maps to 507
 * (Insufficient Storage): a capacity condition, not transient, so unlike concurrency saturation
 * it carries no {@code Retry-After}.
 */
public class TableLimitExceededException extends RuntimeException {
    public TableLimitExceededException(int maxTables) {
        super("Table capacity reached (" + maxTables + " tables); no further uploads accepted.");
    }
}
