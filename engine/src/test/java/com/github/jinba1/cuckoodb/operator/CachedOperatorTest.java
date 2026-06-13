package com.github.jinba1.cuckoodb.operator;

import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.PlanContext;
import com.github.jinba1.cuckoodb.QueryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CachedOperatorTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("N.csv"), List.of("n", "1", "2", "3"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    private static int drain(Operator op) {
        int n = 0;
        while (op.getNextTuple() != null) n++;
        return n;
    }

    @Test
    public void replaysSourceTuplesAndResets() {
        CachedOperator cached = new CachedOperator(new ScanOperator(ctx, "N"));
        assertEquals(3, drain(cached));
        assertNull(cached.getNextTuple());
        cached.reset();
        assertEquals(3, drain(cached));
    }

    @Test
    public void passesThroughSourceSchema() {
        ScanOperator scan = new ScanOperator(ctx, "N");
        String schemaId = scan.propagateSchemaId();
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        CachedOperator cached = new CachedOperator(new ScanOperator(ctx, "N"));
        assertEquals(schemaId, cached.propagateSchemaId());
    }
}
