package com.github.jinba1.cuckoodb.server.web;

/**
 * An uploaded CSV was malformed (bad header, ragged row, duplicate column, non-int in an
 * int column). Maps to 400 — a client mistake, distinct from a query-path {@code DATA_ERROR}
 * (500). The message is sanitized of any server filesystem path before it reaches the client.
 */
public class InvalidUploadException extends RuntimeException {
    public InvalidUploadException(String message) {
        super(message);
    }
}
