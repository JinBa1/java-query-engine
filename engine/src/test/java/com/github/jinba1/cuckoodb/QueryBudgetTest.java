package com.github.jinba1.cuckoodb;

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

    @Test
    public void tupleBreachReportsKindTuples() {
        // The REST layer maps a TUPLES breach to 429 and a TIME breach to 504, so the kind
        // must be programmatically distinguishable, not parsed from the message text.
        QueryBudget budget = new QueryBudget(0L, null);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::charge);
        assertEquals(BudgetKind.TUPLES, ex.kind());
    }

    @Test
    public void timeBreachInChargeReportsKindTime() {
        QueryBudget budget = new QueryBudget(null, 0L);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::charge);
        assertEquals(BudgetKind.TIME, ex.kind());
    }

    @Test
    public void timeBreachInCheckDeadlineReportsKindTime() {
        QueryBudget budget = new QueryBudget(null, 0L);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::checkDeadline);
        assertEquals(BudgetKind.TIME, ex.kind());
    }

    @Test
    public void checkDeadlineWithZeroTimeoutTripsImmediately() {
        QueryBudget budget = new QueryBudget(null, 0L);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::checkDeadline);
        assertTrue(ex.getMessage().contains("Time budget exceeded"), ex.getMessage());
    }

    @Test
    public void checkDeadlineStartsTheClockWhenFirst() throws InterruptedException {
        // a stall before the first charge must still be covered: the check starts the clock
        QueryBudget budget = new QueryBudget(null, 30L);
        Thread.sleep(50); // before the first check; must not count
        budget.checkDeadline(); // clock starts here, deadline 30 ms away: no throw
        Thread.sleep(50);
        assertThrows(QueryBudgetExceededException.class, budget::checkDeadline);
    }

    @Test
    public void checkDeadlineDoesNotCountTuples() {
        QueryBudget budget = new QueryBudget(2L, null);
        for (int i = 0; i < 100; i++) {
            budget.checkDeadline(); // tuple limit must be untouched
        }
        budget.charge();
        budget.charge(); // exactly at limit: fine
        assertEquals(2, budget.processed());
    }

    @Test
    public void checkDeadlineWithNullTimeoutIsANoOp() {
        QueryBudget budget = new QueryBudget(null, null);
        for (int i = 0; i < 100; i++) {
            budget.checkDeadline();
        }
        assertEquals(0, budget.processed());
    }

    @Test
    public void checkDeadlineSeesClockStartedByCharge() throws InterruptedException {
        QueryBudget budget = new QueryBudget(null, 5L);
        budget.charge(); // starts the clock
        Thread.sleep(50);
        assertThrows(QueryBudgetExceededException.class, budget::checkDeadline);
    }
}
