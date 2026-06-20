package com.github.jinba1.cuckoodb.server.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.server.web.dto.TableColumnDto;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The shared catalog-column → DTO mapping, used by the REST describe endpoint and the MCP
 * {@code describe_table} tool so both render an identical schema shape. The type name is the enum
 * name ({@code INT} / {@code STRING}); a null catalog type maps to a null type string (an
 * empty-result column has no inferred type).
 */
class CatalogMapperTest {

    @Test
    void mapsIntAndStringTypesInColumnOrder() {
        List<TableColumnDto> dtos = CatalogMapper.toDto(List.of(
                new CatalogFacade.TableColumn("id", ColumnType.INT),
                new CatalogFacade.TableColumn("name", ColumnType.STRING)));

        assertEquals(2, dtos.size());
        assertEquals("id", dtos.get(0).name());
        assertEquals("INT", dtos.get(0).type());
        assertEquals("name", dtos.get(1).name());
        assertEquals("STRING", dtos.get(1).type());
    }

    @Test
    void nullColumnTypeMapsToNullTypeString() {
        List<TableColumnDto> dtos = CatalogMapper.toDto(List.of(
                new CatalogFacade.TableColumn("mystery", null)));

        assertEquals("mystery", dtos.get(0).name());
        assertNull(dtos.get(0).type());
    }

    @Test
    void emptyColumnsMapToEmptyList() {
        assertEquals(List.of(), CatalogMapper.toDto(List.of()));
    }
}
