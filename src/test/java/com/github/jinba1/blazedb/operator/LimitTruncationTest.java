package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.QueryBudget;
import com.github.jinba1.blazedb.QueryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * LimitOperator must distinguish "truncated at N" from "exactly N rows existed":
 * after emitting its cap it peeks the child once, so agents can trust the flag.
 */
public class LimitTruncationTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("T.csv"), List.of("a", "1", "2", "3"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    private static long drain(Operator op) {
        long count = 0;
        while (op.getNextTuple() != null) count++;
        return count;
    }

    @Test
    public void childWithMoreRowsReportsTruncated() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 2);
        assertEquals(2, drain(limit));
        assertTrue(limit.wasTruncated());
    }

    @Test
    public void exactlyLimitRowsIsNotTruncated() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 3);
        assertEquals(3, drain(limit));
        assertFalse(limit.wasTruncated(), "exactly-N result must not report truncation");
    }

    @Test
    public void fewerThanLimitRowsIsNotTruncated() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 10);
        assertEquals(3, drain(limit));
        assertFalse(limit.wasTruncated());
    }

    @Test
    public void truncationUnknownBeforeDrainCompletes() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 2);
        assertNotNull(limit.getNextTuple());
        assertFalse(limit.wasTruncated(), "no claim before the peek has run");
    }

    @Test
    public void resetClearsPeekState() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 2);
        drain(limit);
        assertTrue(limit.wasTruncated());

        limit.reset();
        assertFalse(limit.wasTruncated());
        assertEquals(2, drain(limit));
        assertTrue(limit.wasTruncated());
    }

    @Test
    public void dataErrorDuringPeekMeansTruncatedNotKilled() throws IOException {
        // Rows past the cap are not part of the answer; a malformed row there must
        // not fail the already-complete capped result. Corrupt row 3 after catalog
        // init (column already inferred as int) so only the peek's read trips.
        Files.writeString(tempDb.resolve("data").resolve("T.csv"), "a\n1\n2\nnot-an-int\n");
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 2);

        assertEquals(2, drain(limit));
        assertTrue(limit.wasTruncated());
    }

    @Test
    public void budgetExhaustionDuringPeekMeansTruncatedNotKilled() {
        // 3-row scan, LIMIT 2: emitting both rows costs 4 charges (2 scan + 2 limit);
        // the peek's third scan row is charge 5 and trips the budget. The query already
        // produced its complete capped result — the probe must not kill it.
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "T"), 2);
        limit.attachBudget(new QueryBudget(4L, null));

        assertEquals(2, drain(limit));
        assertTrue(limit.wasTruncated());
    }
}
