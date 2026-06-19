package com.github.jinba1.cuckoodb.server.web;

/**
 * Upload is disabled ({@code cuckoodb.upload.enabled=false}). Maps to 404, not 403: a disabled
 * write surface is treated as not mounted, so its existence is not advertised before governance.
 */
public class UploadDisabledException extends RuntimeException {
    public UploadDisabledException() {
        super("No such resource.");
    }
}
