package com.github.jinba1.cuckoodb.server.web;

/**
 * The table named in a {@code GET /tables/{name}} URL does not exist. Maps to 404: here the
 * table IS the addressed resource (unlike a query body's unknown table, which is a 422).
 */
public class TableNotFoundException extends RuntimeException {
    public TableNotFoundException(String name) {
        super("Table '" + name + "' not found.");
    }
}
