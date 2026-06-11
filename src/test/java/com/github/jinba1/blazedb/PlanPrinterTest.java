package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests for operator describe() lines and PlanPrinter tree rendering.
 * Plans are built through the real planner for fidelity.
 */
public class PlanPrinterTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("Sales.csv"), List.of("region,qty", "east,10", "west,7"));
        Files.write(data.resolve("Reps.csv"), List.of("region,name", "east,alice", "west,bob"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
    }

    private Operator plan(String sql) throws IOException {
        Path q = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(q, sql);
        return QueryPlanner.parseStatement(q.toString());
    }

    @Test
    public void rendersLinearPipeline() throws IOException {
        boolean original = Constants.useQueryOptimization;
        Constants.useQueryOptimization = false; // deterministic unoptimized shape
        try {
            Operator root = plan(
                    "SELECT Sales.region FROM Sales WHERE Sales.qty > 5 ORDER BY Sales.region LIMIT 1;");
            String text = PlanPrinter.print(root);
            assertEquals(
                    "Limit[1]\n"
                            + "  Sort[Sales.region]\n"
                            + "    Project[Sales.region]\n"
                            + "      Select[Sales.qty > 5]\n"
                            + "        Scan[Sales]\n",
                    text);
        } finally {
            Constants.useQueryOptimization = original;
        }
    }

    @Test
    public void rendersJoinWithTwoIndentedChildren() throws IOException {
        boolean original = Constants.useQueryOptimization;
        Constants.useQueryOptimization = false;
        try {
            Operator root = plan(
                    "SELECT * FROM Sales, Reps WHERE Sales.region = Reps.region;");
            String text = PlanPrinter.print(root);
            assertEquals(
                    "HashJoin[Sales.region = Reps.region]\n"
                            + "  Scan[Sales]\n"
                            + "  Scan[Reps]\n",
                    text);
        } finally {
            Constants.useQueryOptimization = original;
        }
    }

    @Test
    public void describesAggregateAndDistinct() throws IOException {
        boolean original = Constants.useQueryOptimization;
        Constants.useQueryOptimization = false;
        try {
            Operator agg = plan(
                    "SELECT Sales.region, COUNT(*) FROM Sales GROUP BY Sales.region;");
            String aggText = PlanPrinter.print(agg);
            assertTrue(aggText.startsWith("Aggregate[group by: Sales.region; calls: COUNT(*)]\n"),
                    aggText);

            Operator distinct = plan("SELECT DISTINCT Sales.region FROM Sales;");
            assertTrue(PlanPrinter.print(distinct).startsWith("Distinct\n"));
        } finally {
            Constants.useQueryOptimization = original;
        }
    }

    @Test
    public void describesCrossJoinAndPlainAggregate() throws IOException {
        boolean original = Constants.useQueryOptimization;
        Constants.useQueryOptimization = false;
        try {
            Operator cross = plan("SELECT * FROM Sales, Reps;");
            assertTrue(PlanPrinter.print(cross).startsWith("Join[cross]\n"));

            Operator agg = plan("SELECT COUNT(*) FROM Sales;");
            assertTrue(PlanPrinter.print(agg).startsWith("Aggregate[calls: COUNT(*)]\n"));
        } finally {
            Constants.useQueryOptimization = original;
        }
    }
}
