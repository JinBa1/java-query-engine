package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CuckooDB.execute returns token-awareness metadata for the caller (CLI today,
 * the REST gateway later): rows written, a trustworthy truncated flag, and a
 * refine hint when truncated. The output file itself stays pure data.
 */
public class QueryResultTest {

    @TempDir
    Path tempDir;

    private Path db;

    @BeforeEach
    public void setUp() throws IOException {
        db = tempDir.resolve("db");
        Path data = db.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("T.csv"), List.of("a", "1", "2", "3"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(db.toString());
    }

    private QueryResult execute(String sql) throws IOException {
        Path file = tempDir.resolve("q.sql");
        Files.writeString(file, sql);
        PlannedQuery planned = QueryPlanner.planQuery(file.toString());
        return CuckooDB.execute(planned.root(), tempDir.resolve("out.csv").toString());
    }

    @Test
    public void unlimitedQueryReportsRowsAndNoTruncation() throws IOException {
        QueryResult result = execute("SELECT * FROM T");
        assertEquals(3, result.rows());
        assertFalse(result.truncated());
        assertNull(result.hint());
    }

    @Test
    public void truncatingLimitReportsTruncatedWithHint() throws IOException {
        QueryResult result = execute("SELECT * FROM T LIMIT 2");
        assertEquals(2, result.rows());
        assertTrue(result.truncated());
        assertNotNull(result.hint());
        assertTrue(result.hint().contains("truncated"), result.hint());
        assertTrue(result.hint().contains("LIMIT"), result.hint());
    }

    @Test
    public void exactLimitIsNotTruncated() throws IOException {
        QueryResult result = execute("SELECT * FROM T LIMIT 3");
        assertEquals(3, result.rows());
        assertFalse(result.truncated());
        assertNull(result.hint());
    }

    @Test
    public void outputFileContainsOnlyDataNoMetadata() throws IOException {
        execute("SELECT * FROM T LIMIT 2");
        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"));
        assertEquals(List.of("a", "1", "2"), lines, "metadata must not pollute the result file");
    }
}
