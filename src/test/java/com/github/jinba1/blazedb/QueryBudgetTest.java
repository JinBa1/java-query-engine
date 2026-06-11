package com.github.jinba1.blazedb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QueryBudgetTest {

    @Test
    public void tupleLimitAllowsExactlyLimitCharges() {
        QueryBudget budget = new QueryBudget(3L, null);
        budget.charge();
        budget.charge();
        budget.charge(); // exactly at limit: fine
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::charge);
        assertTrue(ex.getMessage().contains("Tuple budget exceeded"), ex.getMessage());
        assertTrue(ex.getMessage().contains("limit 3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("4"), ex.getMessage());
    }

    @Test
    public void timeLimitKillsAfterDeadline() throws InterruptedException {
        QueryBudget budget = new QueryBudget(null, 5L);
        budget.charge(); // starts the clock; deadline 5 ms away
        Thread.sleep(50);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::charge);
        assertTrue(ex.getMessage().contains("Time budget exceeded"), ex.getMessage());
        assertTrue(ex.getMessage().contains("5 ms"), ex.getMessage());
    }

    @Test
    public void zeroTimeoutTripsOnTheFirstCharge() {
        // deadline == now must count as expired, independent of clock resolution
        QueryBudget budget = new QueryBudget(null, 0L);
        assertThrows(QueryBudgetExceededException.class, budget::charge);
    }

    @Test
    public void clockStartsAtFirstChargeNotConstruction() throws InterruptedException {
        QueryBudget budget = new QueryBudget(null, 5_000L);
        Thread.sleep(20); // construction-to-first-charge delay must not count
        budget.charge();
        budget.charge(); // would throw if the clock had started at construction with a tiny budget
        // now prove the deadline is measured from first charge with a tight window:
        QueryBudget tight = new QueryBudget(null, 30L);
        Thread.sleep(50);       // sleep BEFORE first charge
        tight.charge();         // must NOT throw: clock starts here
        assertEquals(1, tight.processed());
    }

    @Test
    public void nullLimitsMeanUnlimited() {
        QueryBudget budget = new QueryBudget(null, null);
        for (int i = 0; i < 100_000; i++) {
            budget.charge();
        }
        assertEquals(100_000, budget.processed());
    }

    @Test
    public void exceptionIsAQueryExecutionException() {
        assertTrue(QueryExecutionException.class.isAssignableFrom(QueryBudgetExceededException.class));
    }
}
