package com.github.jinba1.cuckoodb.server.catalog;

import com.github.jinba1.cuckoodb.server.web.dto.TableColumnDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a base table's catalog-authoritative columns to the wire DTO, shared by the REST describe
 * endpoint and the MCP {@code describe_table} tool so both render an identical schema shape. The
 * type is the {@link com.github.jinba1.cuckoodb.ColumnType} enum name, or null when the catalog
 * has no inferred type for a column.
 */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    /** Catalog columns → DTOs, in column order, preserving a null type as a null type string. */
    public static List<TableColumnDto> toDto(List<CatalogFacade.TableColumn> columns) {
        List<TableColumnDto> dtos = new ArrayList<>(columns.size());
        for (CatalogFacade.TableColumn c : columns) {
            dtos.add(new TableColumnDto(c.name(), c.type() == null ? null : c.type().name()));
        }
        return dtos;
    }
}
