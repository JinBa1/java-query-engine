package com.github.jinba1.cuckoodb.server.audit;

/**
 * Receives one {@link AuditEvent} per query attempt. The interface (and the event shape) are
 * defined now so the {@code QueryService} call site is permanent; a later governance phase
 * supplies a persistent implementation. The default {@link NoOpAuditSink} discards events.
 */
public interface AuditSink {
    void record(AuditEvent event);
}
