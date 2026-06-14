package com.github.jinba1.cuckoodb.server.audit;

/**
 * One auditable query attempt, captured at the {@code QueryService} choke point after planning
 * (so the plan is in scope and a later governance phase need not re-plan). The shape is fixed now
 * even though the sink is a no-op, so wiring a persistent audit log later is a sink swap, not an
 * API change.
 *
 * <p>Exactly one of {@code rowCount} / {@code errorCode} is meaningful per outcome:
 * a success carries {@code rowCount} (and {@code truncated}); a failure carries {@code errorCode}.
 *
 * @param principal   the caller label (anonymous until authentication is added)
 * @param sql         the submitted SQL text
 * @param explainText the rendered plan for an EXPLAIN request, else null
 * @param outcome     SUCCESS / EXPLAIN / ERROR
 * @param rowCount    rows returned on success (null otherwise)
 * @param truncated   whether a LIMIT truncated the result (false for explain/error)
 * @param errorCode   the engine {@code ErrorCode} name on failure (null otherwise)
 */
public record AuditEvent(
        String principal,
        String sql,
        String explainText,
        Outcome outcome,
        Long rowCount,
        boolean truncated,
        String errorCode) {

    public enum Outcome { SUCCESS, EXPLAIN, ERROR }

    public static AuditEvent success(String principal, String sql, long rowCount, boolean truncated) {
        return new AuditEvent(principal, sql, null, Outcome.SUCCESS, rowCount, truncated, null);
    }

    public static AuditEvent explain(String principal, String sql, String explainText) {
        return new AuditEvent(principal, sql, explainText, Outcome.EXPLAIN, null, false, null);
    }

    public static AuditEvent error(String principal, String sql, String errorCode) {
        return new AuditEvent(principal, sql, null, Outcome.ERROR, null, false, errorCode);
    }
}
