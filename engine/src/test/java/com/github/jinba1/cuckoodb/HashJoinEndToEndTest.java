package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class HashJoinEndToEndTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("Sales.csv"), List.of("region,qty", "east,10", "west,7", "east,3"));
        Files.write(data.resolve("Reps.csv"), List.of("region,name", "east,alice", "west,bob"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
    }

    private PlannedQuery planFile(String sql) throws IOException {
        Path q = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(q, sql);
        return QueryPlanner.planQuery(q.toString());
    }

    @Test
    public void plannerSelectsHashJoinForEquiJoin() throws IOException {
        PlannedQuery planned = planFile(
                "EXPLAIN SELECT * FROM Sales, Reps WHERE Sales.region = Reps.region;");
        assertTrue(planned.explainText().contains("HashJoin[Sales.region = Reps.region]"),
                planned.explainText());
    }

    @Test
    public void crossProductStaysNestedLoop() throws IOException {
        PlannedQuery planned = planFile("EXPLAIN SELECT * FROM Sales, Reps;");
        assertTrue(planned.explainText().contains("Join[cross]"), planned.explainText());
        assertFalse(planned.explainText().contains("HashJoin"), planned.explainText());
    }

    @Test
    public void flagDisabledFallsBackToNestedLoop() throws IOException {
        Path q = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(q,
                "EXPLAIN SELECT * FROM Sales, Reps WHERE Sales.region = Reps.region;");
        PlannedQuery planned = QueryPlanner.planQuery(q.toString(), new QueryConfig(true, false));
        assertFalse(planned.explainText().contains("HashJoin"), planned.explainText());
        assertTrue(planned.explainText().contains("Join[Sales.region = Reps.region]"),
                planned.explainText());
    }

    @Test
    public void equiPlusResidualQueryReturnsCorrectRows() throws IOException {
        Path q = tempDb.resolve("res.sql");
        Files.writeString(q,
                "SELECT Reps.name FROM Sales, Reps "
                        + "WHERE Sales.region = Reps.region AND Sales.qty > 5;");
        Path out = tempDb.resolve("res-out.csv");
        var root = QueryPlanner.parseStatement(q.toString());
        CuckooDB.execute(root, out.toString());
        List<String> lines = null;
        try {
            lines = Files.readAllLines(out);
        } catch (IOException e) {
            fail(e);
        }
        assertEquals(3, lines.size(), lines.toString()); // header + alice(east,10) + bob(west,7)
        assertTrue(lines.contains("alice"), lines.toString());
        assertTrue(lines.contains("bob"), lines.toString());
    }
}
