package com.github.jinba1.cuckoodb.server.query;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Resolves the calling principal from the {@code X-Api-Key} header. Today it only echoes the
 * key (or anonymous); a later governance phase turns this into real authentication and per-key
 * policy. It exists now so the audit trail and budget policy can be keyed on a principal without
 * a later signature change.
 */
@Component
public class ApiPrincipalResolver {

    /** Header carrying the (currently unauthenticated) API key. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    /** Principal label used when no API key is presented. */
    public static final String ANONYMOUS = "anonymous";

    /** The presented API key, if any — not yet validated. */
    public Optional<String> apiKey(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(key.strip());
    }

    /** A principal label for audit/budget keying; {@link #ANONYMOUS} when no key is presented. */
    public String resolve(HttpServletRequest request) {
        return apiKey(request).orElse(ANONYMOUS);
    }
}
