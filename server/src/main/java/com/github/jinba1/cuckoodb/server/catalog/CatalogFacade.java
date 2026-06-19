package com.github.jinba1.cuckoodb.server.catalog;

import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.TableMeta;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server's only doorway to the engine's static {@link DBCatalog} singleton. Wrapping it
 * in a Spring bean gives controllers a mockable seam (the static singleton cannot be mocked)
 * and keeps every catalog read/write in one auditable place. The bean holds no state of its
 * own — the catalog instance lifecycle is owned by {@code CatalogInitializer}.
 */
@Component
public class CatalogFacade {

    /** Table names currently in the catalog, sorted. */
    public List<String> tableNames() {
        return DBCatalog.getInstance().getTableNames();
    }

    /** Whether a table of this exact name is registered. */
    public boolean exists(String name) {
        return DBCatalog.getInstance().tableExists(name);
    }

    /** Current registered-table count, for the upload table-count cap. */
    public int tableCount() {
        return DBCatalog.getInstance().getTableNames().size();
    }

    /**
     * The static, catalog-authoritative typed schema of a base table, in column order, or
     * {@link Optional#empty()} if the table is absent. Schema discovery must use this — never a
     * query result's best-effort inferred types, which are null on an empty result.
     */
    public Optional<List<TableColumn>> columnsOf(String name) {
        TableMeta meta = DBCatalog.getInstance().getTableMeta(name);
        if (meta == null) {
            return Optional.empty();
        }
        Map<String, Integer> schema = meta.schema();
        List<ColumnType> types = meta.types();
        String[] byIndex = new String[types.size()];
        for (Map.Entry<String, Integer> e : schema.entrySet()) {
            int idx = e.getValue();
            if (idx >= 0 && idx < byIndex.length) {
                byIndex[idx] = e.getKey();
            }
        }
        List<TableColumn> columns = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            columns.add(new TableColumn(byIndex[i], types.get(i)));
        }
        return Optional.of(columns);
    }

    /**
     * Atomically enforces the table-count cap and registers a table from an already-written CSV.
     * The count check and the register are one critical section, serialized on this singleton bean
     * — the sole request-time write path — so concurrent uploads with distinct names cannot each
     * pass a separate count check and overshoot the cap. The cap is therefore a hard ceiling, not
     * a soft one.
     *
     * <p>Returns {@link RegistrationOutcome#OVER_CAP} when the catalog already holds {@code
     * maxTables} (nothing is registered), {@link RegistrationOutcome#NAME_TAKEN} when the name is
     * already in use (the 409 signal), or {@link RegistrationOutcome#REGISTERED} on success. Throws
     * {@code QueryExecutionException(DATA_ERROR)} for a malformed CSV. The file must outlive every
     * query that scans the table.
     */
    public synchronized RegistrationOutcome registerIfUnderCap(String name, Path csv, int maxTables) {
        if (tableCount() >= maxTables) {
            return RegistrationOutcome.OVER_CAP;
        }
        return DBCatalog.getInstance().registerTable(name, csv)
                ? RegistrationOutcome.REGISTERED
                : RegistrationOutcome.NAME_TAKEN;
    }

    /** Result of an {@link #registerIfUnderCap} attempt. */
    public enum RegistrationOutcome {
        /** The table was registered under the requested name. */
        REGISTERED,
        /** A table of that name already exists; nothing changed (HTTP 409). */
        NAME_TAKEN,
        /** The catalog is already at the table-count cap; nothing registered (HTTP 507). */
        OVER_CAP
    }

    /** One column of a base table's static schema: bare name + catalog-inferred type. */
    public record TableColumn(String name, ColumnType type) {
    }
}
