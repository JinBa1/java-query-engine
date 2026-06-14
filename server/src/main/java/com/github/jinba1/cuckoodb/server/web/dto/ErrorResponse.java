package com.github.jinba1.cuckoodb.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body. {@code errorCode} is a stable machine-readable category (an engine
 * {@code ErrorCode} name or a server-side code); {@code message} is agent-legible for 4xx and
 * generic for 5xx. {@code errorId} is set only for 5xx, correlating the generic client message
 * with the full detail logged server-side (raw engine messages can embed filesystem paths and
 * are never serialized).
 *
 * @param errorCode stable failure category
 * @param message   human/agent readable explanation (generic for 5xx)
 * @param errorId   server-log correlation id for 5xx; null (omitted) otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String errorCode, String message, String errorId) {

    public static ErrorResponse of(String errorCode, String message) {
        return new ErrorResponse(errorCode, message, null);
    }

    public static ErrorResponse withId(String errorCode, String message, String errorId) {
        return new ErrorResponse(errorCode, message, errorId);
    }
}
