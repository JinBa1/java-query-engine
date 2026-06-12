package com.github.jinba1.blazedb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end tests for the SQL-surface slice: COUNT/AVG/MIN/MAX aggregates and LIMIT.
 * Drives the engine via QueryPlanner.parseStatement + BlazeDB.execute so that both
 * planner-time and runtime QueryExecutionExceptions are observable.
 */
public class AggregateLimitEndToEndTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() {
        DBCatalog.resetDBCatalog();
    }

    private void writeTable(String name, String... lines) throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve(name + ".csv"), List.of(lines));
    }

    /** Sales: region STRING, qty INT. Two regions; east has 2 rows, west has 1. */
    private void writeSales() throws IOException {
        writeTable("Sales",
                "region,qty",
                "east,10",
                "west,7",
                "east,3");
    }

    /** Runs a SQL string end-to-end and returns output lines (header first). */
    private List<String> run(String sql) throws IOException {
        Path query = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(query, sql);
        Path out = tempDb.resolve("out-" + System.nanoTime() + ".csv");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        var root = QueryPlanner.parseStatement(query.toString());
        BlazeDB.execute(root, out.toString());
        return Files.readAllLines(out);
    }

    @Test
    public void planningErrorsPropagateToCaller() throws IOException {
        writeSales();
        Path query = tempDb.resolve("bad.sql");
        Files.writeString(query, "SELECT SUM(*) FROM Sales;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("SUM(*)"), ex.getMessage());
    }

    @Test
    public void unqualifiedAggregateArgumentIsRejectedAtPlanTime() throws IOException {
        // The engine resolves columns by table qualifier throughout; unqualified
        // arguments used to die later with an opaque NPE — must be a plan-time error
        writeSales();
        Path query = tempDb.resolve("unqualified.sql");
        Files.writeString(query, "SELECT SUM(qty) FROM Sales;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("qualified column names"), ex.getMessage());
    }

    @Test
    public void multiArgumentAggregateIsRejectedAtPlanTime() throws IOException {
        writeSales();
        Path query = tempDb.resolve("multi.sql");
        Files.writeString(query, "SELECT SUM(Sales.qty, Sales.region) FROM Sales;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("exactly one argument"), ex.getMessage());
    }

    @Test
    public void countStarAndCountColumnAreEqual() throws IOException {
        writeSales();
        List<String> lines = run("SELECT COUNT(*), COUNT(Sales.qty) FROM Sales;");
        assertEquals(List.of("count(*),count(sales.qty)", "3,3"), lines);
    }

    @Test
    public void countStarAloneNeedsNoInputColumns() throws IOException {
        // No WHERE, no GROUP BY, no argument columns: the required-column set is empty,
        // exercising the skip-projection guard in the planner.
        writeSales();
        List<String> lines = run("SELECT COUNT(*) FROM Sales;");
        assertEquals(List.of("count(*)", "3"), lines);
    }

    @Test
    public void mixedAggregatesWithGroupBy() throws IOException {
        writeSales();
        List<String> lines = run(
                "SELECT Sales.region, COUNT(*), MIN(Sales.qty), MAX(Sales.qty), AVG(Sales.qty) "
                        + "FROM Sales GROUP BY Sales.region;");
        assertEquals("region,count(*),min(sales.qty),max(sales.qty),avg(sales.qty)", lines.get(0));
        assertTrue(lines.contains("east,2,3,10,6"), lines.toString());  // avg(10,3) = 13/2 -> 6
        assertTrue(lines.contains("west,1,7,7,7"), lines.toString());
        assertEquals(3, lines.size());
    }

    @Test
    public void minMaxOnStringColumn() throws IOException {
        writeSales();
        List<String> lines = run("SELECT MIN(Sales.region), MAX(Sales.region) FROM Sales;");
        assertEquals(List.of("min(sales.region),max(sales.region)", "east,west"), lines);
    }

    @Test
    public void aggregateOnEmptyInputReturnsZeroRows() throws IOException {
        writeTable("Empty", "a,b");
        List<String> lines = run("SELECT COUNT(*), MIN(Empty.a) FROM Empty;");
        assertEquals(List.of("count(*),min(empty.a)"), lines); // header only
    }

    @Test
    public void bareColumnWithAggregatesAndNoGroupByIsRejected() throws IOException {
        writeSales();
        Path query = tempDb.resolve("bare.sql");
        Files.writeString(query, "SELECT Sales.region, COUNT(*) FROM Sales;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("GROUP BY"), ex.getMessage());
        assertTrue(ex.getMessage().contains("region"), ex.getMessage());
    }

    /** Writes the SQL, inits the catalog, and asserts planning rejects it. */
    private QueryExecutionException assertPlanRejected(String sql) throws IOException {
        Path query = tempDb.resolve("rej-" + System.nanoTime() + ".sql");
        Files.writeString(query, sql);
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        return assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
    }

    @Test
    public void selectStarWithAggregatesIsRejected() throws IOException {
        writeSales();
        QueryExecutionException ex = assertPlanRejected("SELECT *, COUNT(*) FROM Sales;");
        assertTrue(ex.getMessage().contains("*"), ex.getMessage());
    }

    @Test
    public void selectStarWithGroupByIsRejected() throws IOException {
        writeSales();
        QueryExecutionException ex = assertPlanRejected(
                "SELECT * FROM Sales GROUP BY Sales.region;");
        assertTrue(ex.getMessage().contains("*"), ex.getMessage());
    }

    @Test
    public void nonAggregateFunctionWithAggregatesIsRejected() throws IOException {
        writeSales();
        QueryExecutionException ex = assertPlanRejected(
                "SELECT UPPER(Sales.region), COUNT(*) FROM Sales;");
        assertTrue(ex.getMessage().contains("UPPER"), ex.getMessage());
    }

    @Test
    public void selectedColumnMissingFromGroupByIsRejected() throws IOException {
        writeSales();
        QueryExecutionException ex = assertPlanRejected(
                "SELECT Sales.region, Sales.qty, COUNT(*) FROM Sales GROUP BY Sales.region;");
        assertTrue(ex.getMessage().contains("qty"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GROUP BY"), ex.getMessage());
    }

    @Test
    public void limitCapsResultAfterOrderBy() throws IOException {
        writeSales();
        List<String> lines = run("SELECT * FROM Sales ORDER BY Sales.qty LIMIT 2;");
        assertEquals(List.of("region,qty", "east,3", "west,7"), lines);
    }

    @Test
    public void limitZeroYieldsHeaderOnly() throws IOException {
        writeSales();
        List<String> lines = run("SELECT * FROM Sales LIMIT 0;");
        assertEquals(List.of("region,qty"), lines);
    }

    @Test
    public void limitBeyondRowCountYieldsAllRows() throws IOException {
        writeSales();
        List<String> lines = run("SELECT * FROM Sales LIMIT 100;");
        assertEquals(4, lines.size()); // header + 3 rows
    }

    @Test
    public void limitAppliesAfterDistinct() throws IOException {
        writeSales();
        List<String> lines = run("SELECT DISTINCT Sales.region FROM Sales LIMIT 2;");
        assertEquals(3, lines.size()); // header + exactly the 2 distinct regions
    }

    @Test
    public void negativeLimitIsRejected() throws IOException {
        // JSqlParser 4.7 parses LIMIT -1 as a SignedExpression (not a LongValue),
        // so our instanceof-LongValue guard fires and throws QueryExecutionException.
        writeSales();
        Path query = tempDb.resolve("neglimit.sql");
        Files.writeString(query, "SELECT * FROM Sales LIMIT -1;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("LIMIT requires a non-negative"), ex.getMessage());
    }
}
