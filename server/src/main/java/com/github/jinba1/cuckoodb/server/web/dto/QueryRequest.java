package com.github.jinba1.cuckoodb.server.web.dto;

/**
 * Request body for {@code POST /queries}.
 *
 * @param sql       the SQL text to plan and execute (EXPLAIN-prefix allowed); required
 * @param maxTuples optional client tuple budget; clamped down to the server cap, defaulted if null
 * @param timeoutMs optional client time budget in ms; clamped down to the server cap, defaulted if null
 */
public record QueryRequest(String sql, Long maxTuples, Long timeoutMs) {
}
