package com.github.jinba1.cuckoodb.operator;

import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.IntValue;
import com.github.jinba1.cuckoodb.PlanContext;
import com.github.jinba1.cuckoodb.QueryBudget;
import com.github.jinba1.cuckoodb.QueryBudgetExceededException;
import com.github.jinba1.cuckoodb.QueryConfig;
import com.github.jinba1.cuckoodb.Tuple;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The time budget must trip inside blocking build/drain phases, not just on tuple
 * emission. Each test uses a stub child that never charges the budget, so with a
 * zero timeout the only way the exception can fire is the deadline check inside
 * the blocking loop itself; tupleCounter == 0 proves no emission happened first.
 */
public class BlockingDrainTimeoutTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("L.csv"), List.of("a", "1", "2", "3"));
        Files.write(data.resolve("R.csv"), List.of("b", "1", "1", "2"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    /** Emits canned single-int tuples under a base-table schema; never charges the budget. */
    private static final class StubChild extends Operator {
        private final String tableId;
        private final List<Tuple> rows;
        private int pos = 0;

        StubChild(PlanContext ctx, String tableId, int... values) {
            super(ctx);
            this.tableId = tableId;
            this.rows = new ArrayList<>(values.length);
            for (int v : values) {
                rows.add(new Tuple(List.of(new IntValue(v))));
            }
            this.schemaRegistered = true;
            this.intermediateSchemaId = tableId;
        }

        @Override
        public Tuple getNextTuple() {
            return pos < rows.size() ? rows.get(pos++) : null;
        }

        @Override
        public void reset() {
            pos = 0;
        }

        @Override
        public String propagateSchemaId() {
            return tableId;
        }

        @Override
        public String describe() {
            return "Stub[" + tableId + "]";
        }

        @Override
        protected void registerSchema() {
            schemaRegistered = true;
        }
    }

    private static Column col(String table, String column) {
        return new Column(new Table(table), column);
    }

    @Test
    public void sortBuildPhaseTripsTimeout() {
        StubChild child = new StubChild(ctx, "L", 3, 1, 2);
        SortOperator sort = new SortOperator(ctx, child, List.of(col("L", "a")));
        sort.attachBudget(new QueryBudget(null, 0L));

        assertThrows(QueryBudgetExceededException.class, sort::getNextTuple);
        assertEquals(0, sort.getTupleCount(), "must trip during buffering, before any emission");
    }

    @Test
    public void aggregateBuildPhaseTripsTimeout() {
        StubChild child = new StubChild(ctx, "L", 1, 1, 2);
        AggregateOperator aggregate = new AggregateOperator(
                ctx, child, List.of(col("L", "a")), List.of(), List.of(col("L", "a")));
        aggregate.attachBudget(new QueryBudget(null, 0L));

        assertThrows(QueryBudgetExceededException.class, aggregate::getNextTuple);
        assertEquals(0, aggregate.getTupleCount(), "must trip during accumulation, before any emission");
    }

    @Test
    public void distinctBuildPhaseTripsTimeout() {
        StubChild child = new StubChild(ctx, "L", 1, 1, 2);
        DuplicateEliminationOperator distinct = new DuplicateEliminationOperator(ctx, child);
        distinct.attachBudget(new QueryBudget(null, 0L));

        assertThrows(QueryBudgetExceededException.class, distinct::getNextTuple);
        assertEquals(0, distinct.getTupleCount(), "must trip during dedup, before any emission");
    }

    @Test
    public void hashJoinBuildPhaseTripsTimeout() throws Exception {
        StubChild outer = new StubChild(ctx, "L", 1);
        StubChild inner = new StubChild(ctx, "R", 1, 1, 2);
        HashJoinOperator join = new HashJoinOperator(
                ctx, outer, inner, CCJSqlParserUtil.parseCondExpression("L.a = R.b"));
        join.attachBudget(new QueryBudget(null, 0L));

        assertThrows(QueryBudgetExceededException.class, join::getNextTuple);
        assertEquals(0, join.getTupleCount(), "must trip while draining the build side");
    }

    @Test
    public void hashJoinProbeBucketScanChecksDeadline() throws Exception {
        // Build completes without a budget; the deadline then trips inside the
        // bucket-scan loop (a skewed key's bucket can spin long between outer pulls).
        StubChild outer = new StubChild(ctx, "L", 1);
        StubChild inner = new StubChild(ctx, "R", 1, 1); // two build rows share key 1
        HashJoinOperator join = new HashJoinOperator(
                ctx, outer, inner, CCJSqlParserUtil.parseCondExpression("L.a = R.b"));

        assertNotNull(join.getNextTuple()); // prepare() + first match; bucket still has one row

        join.attachBudget(new QueryBudget(null, 0L));
        assertThrows(QueryBudgetExceededException.class, join::getNextTuple);
    }

    @Test
    public void blockingOperatorsStillCompleteWithoutBudget() throws Exception {
        SortOperator sort = new SortOperator(
                ctx, new StubChild(ctx, "L", 3, 1, 2), List.of(col("L", "a")));
        int count = 0;
        while (sort.getNextTuple() != null) count++;
        assertEquals(3, count);
    }
}
