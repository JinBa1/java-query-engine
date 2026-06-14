package com.github.jinba1.cuckoodb.server.web;

/**
 * An upload targeted a table name already registered. Maps to 409 — the engine's atomic
 * {@code putIfAbsent} lost, so the existing table is left untouched.
 */
public class TableAlreadyExistsException extends RuntimeException {
    public TableAlreadyExistsException(String name) {
        super("Table '" + name + "' already exists.");
    }
}
