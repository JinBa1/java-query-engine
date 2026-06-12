package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.PlanContext;
import com.github.jinba1.blazedb.QueryConfig;
import com.github.jinba1.blazedb.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LimitOperatorTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("Nums.csv"), List.of("n", "1", "2", "3", "4", "5"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    private static int drain(Operator op) {
        int count = 0;
        while (op.getNextTuple() != null) count++;
        return count;
    }

    @Test
    public void emitsAtMostLimitTuples() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "Nums"), 3);
        assertEquals(3, drain(limit));
        assertNull(limit.getNextTuple()); // stays EOF
    }

    @Test
    public void limitZeroEmitsNothing() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "Nums"), 0);
        assertNull(limit.getNextTuple());
    }

    @Test
    public void limitLargerThanInputEmitsAll() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "Nums"), 99);
        assertEquals(5, drain(limit));
    }

    @Test
    public void resetRestartsTheWindow() {
        LimitOperator limit = new LimitOperator(ctx, new ScanOperator(ctx, "Nums"), 2);
        assertEquals(2, drain(limit));
        limit.reset();
        Tuple first = limit.getNextTuple();
        assertNotNull(first);
        assertEquals(2, 1 + drain(limit));
    }
}
