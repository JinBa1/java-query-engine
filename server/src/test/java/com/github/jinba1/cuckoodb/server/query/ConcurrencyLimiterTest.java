package com.github.jinba1.cuckoodb.server.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;

import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    private ConcurrencyLimiter withPermits(int n) {
        return new ConcurrencyLimiter(new CuckooDbProperties(null, null, n, null, null));
    }

    @Test
    void runsWorkAndReleasesPermit() {
        ConcurrencyLimiter limiter = withPermits(1);
        assertEquals("ok", limiter.withPermit(() -> "ok"));
        assertEquals(1, limiter.availablePermits(), "permit released after work");
    }

    @Test
    void saturationFailsFast() {
        // One permit, already held by the outer call: the nested acquire must fail, not block.
        ConcurrencyLimiter limiter = withPermits(1);
        assertThrows(ConcurrencyLimitExceededException.class,
                () -> limiter.withPermit(() -> limiter.withPermit(() -> "inner")));
    }

    @Test
    void releasesPermitEvenWhenWorkThrows() {
        ConcurrencyLimiter limiter = withPermits(1);
        assertThrows(RuntimeException.class,
                () -> limiter.withPermit(() -> { throw new RuntimeException("boom"); }));
        assertEquals(1, limiter.availablePermits(), "permit released on failure too");
    }
}
