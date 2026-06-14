package com.github.jinba1.cuckoodb.server.web.dto;

/**
 * One column of a base table's static, catalog-authoritative schema.
 *
 * @param name the bare column name
 * @param type the catalog-inferred type ({@code INT} / {@code STRING})
 */
public record TableColumnDto(String name, String type) {
}
