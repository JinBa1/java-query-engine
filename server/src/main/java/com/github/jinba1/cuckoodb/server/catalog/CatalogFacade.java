package com.github.jinba1.cuckoodb.server.catalog;

import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.TableMeta;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * {@code null} if the table is absent. Schema discovery must use this — never a query
     * result's best-effort inferred types, which are null on an empty result.
     */
    public List<TableColumn> columnsOf(String name) {
        TableMeta meta = DBCatalog.getInstance().getTableMeta(name);
        if (meta == null) {
            return null;
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
        return columns;
    }

    /**
     * Registers a table from an already-written CSV file. Returns {@code false} when the name
     * is already taken (the 409 signal); throws {@code QueryExecutionException(DATA_ERROR)} for
     * a malformed CSV. The file must outlive every query that scans the table.
     */
    public boolean register(String name, Path csv) {
        return DBCatalog.getInstance().registerTable(name, csv);
    }

    /** One column of a base table's static schema: bare name + catalog-inferred type. */
    public record TableColumn(String name, ColumnType type) {
    }
}
