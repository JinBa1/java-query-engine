package com.github.jinba1.cuckoodb.server.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The shared table-name guard, used by the REST upload path and the MCP tools so both enforce one
 * charset. Mirrors {@code ^[A-Za-z0-9_]{1,64}$}: it blocks path-traversal shapes (dot, slash) and
 * odd characters before any name reaches the engine or a filesystem path.
 */
class TableNameValidatorTest {

    @Test
    void acceptsAlphanumericAndUnderscore() {
        assertDoesNotThrow(() -> TableNameValidator.validate("Student"));
        assertDoesNotThrow(() -> TableNameValidator.validate("student_2024"));
        assertDoesNotThrow(() -> TableNameValidator.validate("ABC_123_xyz"));
    }

    @Test
    void acceptsSingleCharAndMaxLengthBoundaries() {
        assertDoesNotThrow(() -> TableNameValidator.validate("a"));
        assertDoesNotThrow(() -> TableNameValidator.validate("_"));
        assertDoesNotThrow(() -> TableNameValidator.validate("a".repeat(64)));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TableNameValidator.validate(null));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> TableNameValidator.validate(""));
    }

    @Test
    void rejectsOverMaxLength() {
        assertThrows(IllegalArgumentException.class,
                () -> TableNameValidator.validate("a".repeat(65)));
    }

    @Test
    void rejectsPathTraversalAndOddCharacters() {
        // Dot and slash are the path-traversal shapes; space/dash/semicolon are SQL-injection bait.
        for (String bad : new String[] {"a.b", "../etc", "a/b", "a b", "a-b", "a;b", "DROP TABLE",
                "tab\tname", "naïve"}) {
            assertThrows(IllegalArgumentException.class, () -> TableNameValidator.validate(bad),
                    "should reject: " + bad);
        }
    }
}
