package com.github.jinba1.cuckoodb.server.web;

/**
 * An upload body exceeded the configured size cap while streaming. Maps to 413. Detected during
 * the write so an oversized body is never fully buffered in memory.
 */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(long maxBytes) {
        super("Upload exceeds the maximum size of " + maxBytes + " bytes.");
    }
}
