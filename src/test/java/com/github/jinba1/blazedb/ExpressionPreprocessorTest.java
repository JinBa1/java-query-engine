package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ExpressionPreprocessorTest {

    private static final String TEST_DB_DIR = "src/test/resources/testdb_ep";
    private static final String SCHEMA_FILE = TEST_DB_DIR + "/schema.txt";
    private static final String DATA_DIR = TEST_DB_DIR + "/data";

    @BeforeEach
    public void setUp() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        // Two tables: T1(A, B), T2(C, D)
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SCHEMA_FILE))) {
            writer.write("T1 A B\n");
            writer.write("T2 C D\n");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/T1.csv"))) {
            writer.write("1, 10\n");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/T2.csv"))) {
            writer.write("100, 200\n");
        }

        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(TEST_DB_DIR);
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/T1.csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/T2.csv"));
        Files.deleteIfExists(Paths.get(SCHEMA_FILE));
        Files.deleteIfExists(Paths.get(DATA_DIR));
        Files.deleteIfExists(Paths.get(TEST_DB_DIR));
    }

    @Test
    public void testSingleTableSelectionGoesToSelectExpressions() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = 5");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(0, joins.size(), "Single-table condition should have no join expressions");
        assertEquals(1, selects.size(), "Single-table condition should have one select expression");
    }

    @Test
    public void testJoinConditionGoesToJoinExpressions() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = T2.C");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(1, joins.size(), "Two-table condition should be a join expression");
        assertEquals(0, selects.size(), "Two-table condition should have no select expressions");
    }

    @Test
    public void testMixedAndExpressionCorrectlySplit() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        // T1.A = 5 AND T1.B = T2.D
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = 5 AND T1.B = T2.D");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(1, joins.size(), "Should have one join expression (T1.B = T2.D)");
        assertEquals(1, selects.size(), "Should have one select expression (T1.A = 5)");
    }

    @Test
    public void testCompoundAndAllSelections() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A > 1 AND T1.B < 100");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(0, joins.size(), "Both conditions reference same table, no joins");
        assertEquals(2, selects.size(), "Should have two select expressions");
    }

    @Test
    public void testCompoundAndAllJoins() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = T2.C AND T1.B = T2.D");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(2, joins.size(), "Both conditions are cross-table joins");
        assertEquals(0, selects.size(), "No single-table selections");
    }

    @Test
    public void testNestedAndWithJoinAndSelection() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        // Three-way AND: two selections + one join
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A > 1 AND T1.B = T2.D AND T2.C < 50");

        ep.evaluate(expr);

        List<Expression> joins = ep.getJoinExpressions();
        List<Expression> selects = ep.getSelectExpressions();

        assertEquals(1, joins.size(), "Should have one join expression (T1.B = T2.D)");
        assertEquals(2, selects.size(), "Should have two select expressions (T1.A > 1, T2.C < 50)");
    }

    @Test
    public void testEvaluateThrowsOnSecondCall() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = 5");

        ep.evaluate(expr);

        assertThrows(IllegalStateException.class, () -> ep.evaluate(expr),
                "Should throw when evaluate called twice");
    }

    @Test
    public void testGetJoinExpressionsThrowsBeforeEvaluate() {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();

        assertThrows(IllegalStateException.class, ep::getJoinExpressions,
                "Should throw when getJoinExpressions called before evaluate");
    }

    @Test
    public void testGetSelectExpressionsThrowsBeforeEvaluate() {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();

        assertThrows(IllegalStateException.class, ep::getSelectExpressions,
                "Should throw when getSelectExpressions called before evaluate");
    }

    @Test
    public void testUnsupportedColumnThrows() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        // Table NonExistent doesn't exist
        Expression expr = CCJSqlParserUtil.parseExpression("NonExistent.A = 5");

        assertThrows(UnsupportedOperationException.class, () -> ep.evaluate(expr),
                "Should throw for nonexistent table");
    }

    @Test
    public void testUnsupportedColumnInTableThrows() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        // Column Z doesn't exist in T1
        Expression expr = CCJSqlParserUtil.parseExpression("T1.Z = 5");

        assertThrows(UnsupportedOperationException.class, () -> ep.evaluate(expr),
                "Should throw for nonexistent column");
    }

    @Test
    public void testDifferentComparisonOperators() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        // != join
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A != T2.C");

        ep.evaluate(expr);

        assertEquals(1, ep.getJoinExpressions().size());
        assertEquals(0, ep.getSelectExpressions().size());
    }

    @Test
    public void testGreaterThanJoin() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A > T2.C");

        ep.evaluate(expr);

        assertEquals(1, ep.getJoinExpressions().size());
    }

    @Test
    public void testLessThanOrEqualSelection() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A <= 10");

        ep.evaluate(expr);

        assertEquals(0, ep.getJoinExpressions().size());
        assertEquals(1, ep.getSelectExpressions().size());
    }

    @Test
    public void testGreaterThanOrEqualJoin() throws Exception {
        ExpressionPreprocessor ep = new ExpressionPreprocessor();
        Expression expr = CCJSqlParserUtil.parseExpression("T1.B >= T2.D");

        ep.evaluate(expr);

        assertEquals(1, ep.getJoinExpressions().size());
        assertEquals(0, ep.getSelectExpressions().size());
    }
}
