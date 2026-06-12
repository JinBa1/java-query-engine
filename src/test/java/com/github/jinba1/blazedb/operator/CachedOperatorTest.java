package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
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

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("N.csv"), List.of("n", "1", "2", "3"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
    }

    private static int drain(Operator op) {
        int n = 0;
        while (op.getNextTuple() != null) n++;
        return n;
    }

    @Test
    public void replaysSourceTuplesAndResets() {
        CachedOperator cached = new CachedOperator(new ScanOperator("N"));
        assertEquals(3, drain(cached));
        assertNull(cached.getNextTuple());
        cached.reset();
        assertEquals(3, drain(cached));
    }

    @Test
    public void passesThroughSourceSchema() {
        ScanOperator scan = new ScanOperator("N");
        String schemaId = scan.propagateSchemaId();
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        CachedOperator cached = new CachedOperator(new ScanOperator("N"));
        assertEquals(schemaId, cached.propagateSchemaId());
    }
}
