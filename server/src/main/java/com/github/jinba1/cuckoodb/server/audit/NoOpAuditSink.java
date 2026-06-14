package com.github.jinba1.cuckoodb.server.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link AuditSink}: logs each event at debug and otherwise discards it. A later
 * governance phase replaces this with a persistent sink (a bean override, no call-site change).
 */
@Component
public class NoOpAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditSink.class);

    @Override
    public void record(AuditEvent event) {
        log.debug("audit: {}", event);
    }
}
