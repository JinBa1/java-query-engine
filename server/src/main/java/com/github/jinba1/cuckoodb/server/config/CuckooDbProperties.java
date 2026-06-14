package com.github.jinba1.cuckoodb.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Server-side configuration, bound from the {@code cuckoodb.*} property namespace. All
 * resource bounds (budget caps, upload size, table count, concurrency) live here so they are
 * tunable per deployment without code changes, and every default is conservative.
 *
 * @param dataDir        directory whose {@code data/} subdir is loaded into the catalog at
 *                       startup; {@code null} leaves the catalog empty (uploads only)
 * @param workDir        directory where uploaded CSVs are persisted for the process lifetime;
 *                       must NOT be an auto-deleted temp dir because joins re-open table files
 * @param maxConcurrentQueries permits for the query semaphore; a miss returns 429
 * @param query          per-query budget defaults and hard caps
 * @param upload         upload feature flag and limits
 */
@ConfigurationProperties(prefix = "cuckoodb")
public record CuckooDbProperties(
        String dataDir,
        String workDir,
        Integer maxConcurrentQueries,
        @NestedConfigurationProperty Query query,
        @NestedConfigurationProperty Upload upload) {

    public CuckooDbProperties {
        if (workDir == null || workDir.isBlank()) {
            workDir = System.getProperty("java.io.tmpdir") + "/cuckoodb-work";
        }
        if (maxConcurrentQueries == null || maxConcurrentQueries < 1) {
            maxConcurrentQueries = Runtime.getRuntime().availableProcessors();
        }
        if (query == null) {
            query = new Query(null, null, null, null);
        }
        if (upload == null) {
            upload = new Upload(null, null, null);
        }
    }

    /**
     * Per-query budget settings. The server always attaches a budget (the engine has none by
     * default = unlimited), so both a tuple and a time bound are required: a time-only budget
     * would not cap a Sort/HashJoin build heap.
     *
     * @param maxTuplesDefault tuples charged when the request omits {@code maxTuples}
     * @param maxTuplesCap     hard ceiling; a larger request is clamped down to this
     * @param timeoutMsDefault wall-clock ms when the request omits {@code timeoutMs}
     * @param timeoutMsCap     hard ceiling; a larger request is clamped down to this
     */
    public record Query(Long maxTuplesDefault, Long maxTuplesCap,
                        Long timeoutMsDefault, Long timeoutMsCap) {
        public Query {
            if (maxTuplesDefault == null) maxTuplesDefault = 100_000L;
            if (maxTuplesCap == null) maxTuplesCap = 1_000_000L;
            if (timeoutMsDefault == null) timeoutMsDefault = 5_000L;
            if (timeoutMsCap == null) timeoutMsCap = 30_000L;
        }
    }

    /**
     * Upload feature settings. Disabled by default so the write surface stays closed until a
     * deployment opts in (and a later governance phase is added).
     *
     * @param enabled  whether {@code POST /tables/{name}} is mounted at all
     * @param maxBytes per-upload size cap in bytes; a larger body returns 413
     * @param maxTables process-wide table count ceiling; over it returns 507
     */
    public record Upload(Boolean enabled, Long maxBytes, Integer maxTables) {
        /** Absolute ceiling on the configurable per-upload size cap. */
        public static final long HARD_MAX_BYTES = 50L * 1024 * 1024;

        public Upload {
            if (enabled == null) enabled = false;
            if (maxBytes == null) maxBytes = 10L * 1024 * 1024;
            if (maxBytes > HARD_MAX_BYTES) maxBytes = HARD_MAX_BYTES;
            if (maxTables == null || maxTables < 1) maxTables = 100;
        }
    }
}
