package com.github.jinba1.cuckoodb.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One result column's metadata. {@code qualifiedName} disambiguates duplicate bare names from a
 * join {@code SELECT *} (e.g. {@code student.a} vs {@code enrolled.a}); {@code type} is the
 * best-effort inferred type and is null for an empty result. Both are omitted from JSON when null.
 *
 * @param name          the bare header name (not unique across a join result)
 * @param qualifiedName the dotted schema origin, or null when there is none
 * @param type          {@code INT} / {@code STRING}, or null for an empty result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnDto(String name, String qualifiedName, String type) {
}
