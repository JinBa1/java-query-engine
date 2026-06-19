package com.github.jinba1.cuckoodb.server.query;

import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds the number of queries executing at once with a single fair semaphore. The engine is
 * CPU-bound and holds intermediate results in heap, so unbounded concurrency would exhaust
 * memory and thrash the CPU; capping it keeps each admitted query's budget meaningful. A miss
 * is surfaced as a 429 rather than queued indefinitely, so a saturated server fails fast.
 */
@Component
public class ConcurrencyLimiter {

    private final Semaphore semaphore;

    public ConcurrencyLimiter(CuckooDbProperties properties) {
        this.semaphore = new Semaphore(properties.maxConcurrentQueries(), true);
    }

    /**
     * Runs {@code work} while holding one permit, releasing it in a finally. Acquisition is
     * non-blocking: if no permit is free the work never starts.
     * @throws ConcurrencyLimitExceededException if no permit could be acquired
     */
    public <T> T withPermit(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyLimitExceededException("Query admission interrupted");
        }
        if (!acquired) {
            throw new ConcurrencyLimitExceededException(
                    "Server is at its query concurrency limit; retry shortly");
        }
        try {
            return work.get();
        } finally {
            semaphore.release();
        }
    }

    /** Permits currently available — for tests and diagnostics. */
    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
