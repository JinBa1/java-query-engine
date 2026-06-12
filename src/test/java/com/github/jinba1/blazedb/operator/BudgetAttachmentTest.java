package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.QueryBudget;
import com.github.jinba1.blazedb.QueryBudgetExceededException;
import com.github.jinba1.blazedb.QueryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class BudgetAttachmentTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("L.csv"), List.of("a", "1", "2", "3", "4", "5"));
        Files.write(data.resolve("R.csv"), List.of("b", "1", "2", "3", "4", "5"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    @Test
    public void attachBudgetReachesBothJoinChildren() {
        ScanOperator outer = new ScanOperator(ctx, "L");
        ScanOperator inner = new ScanOperator(ctx, "R");
        JoinOperator join = new JoinOperator(ctx, outer, inner, null); // cross product
        QueryBudget budget = new QueryBudget(1_000L, null);

        join.attachBudget(budget);

        // protected field, same package: verify the walk reached every node
        assertSame(budget, join.budget);
        assertSame(budget, outer.budget);
        assertSame(budget, inner.budget);
    }

    @Test
    public void crossProductTripsTupleBudgetDespiteSmallOutput() {
        ScanOperator outer = new ScanOperator(ctx, "L");
        ScanOperator inner = new ScanOperator(ctx, "R");
        JoinOperator join = new JoinOperator(ctx, outer, inner, null); // 5x5 cross product
        // Total work for the full cross product far exceeds 12 (outer 5 + inner re-scans 25 + join 25)
        join.attachBudget(new QueryBudget(12L, null));

        QueryBudgetExceededException ex = assertThrows(QueryBudgetExceededException.class, () -> {
            while (join.getNextTuple() != null) {
                // drain
            }
        });
        assertTrue(ex.getMessage().contains("Tuple budget exceeded"), ex.getMessage());
    }

    @Test
    public void noBudgetMeansUnlimited() {
        ScanOperator outer = new ScanOperator(ctx, "L");
        ScanOperator inner = new ScanOperator(ctx, "R");
        JoinOperator join = new JoinOperator(ctx, outer, inner, null);
        int count = 0;
        while (join.getNextTuple() != null) count++;
        assertEquals(25, count);
    }
}
