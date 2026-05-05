package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import org.junit.jupiter.api.Test;

public class ColumnExtractorTest {

    @Test
    public void testSimpleColumnExtraction() throws Exception {
        Expression expr = CCJSqlParserUtil.parseExpression("A = 5");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(1, columns.size(), "Should extract one column");
        assertEquals("A", columns.get(0).getColumnName(), "Column name should be A");
    }

    @Test
    public void testQualifiedColumnExtraction() throws Exception {
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = T2.B");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(2, columns.size(), "Should extract two columns");

        assertEquals("A", columns.get(0).getColumnName());
        assertEquals("T1", columns.get(0).getTable().getName());
        assertEquals("B", columns.get(1).getColumnName());
        assertEquals("T2", columns.get(1).getTable().getName());
    }

    @Test
    public void testMultipleColumnsInCompoundExpression() throws Exception {
        Expression expr = CCJSqlParserUtil.parseExpression("A > 1 AND B < 10 AND C = 5");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(3, columns.size(), "Should extract three columns");

        assertEquals("A", columns.get(0).getColumnName());
        assertEquals("B", columns.get(1).getColumnName());
        assertEquals("C", columns.get(2).getColumnName());
    }

    @Test
    public void testColumnsInNestedConditions() throws Exception {
        // T1.A = T2.B AND T3.C > 5
        Expression expr = CCJSqlParserUtil.parseExpression("T1.A = T2.B AND T3.C > 5");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(3, columns.size(), "Should extract three columns");

        assertEquals("T1", columns.get(0).getTable().getName());
        assertEquals("A", columns.get(0).getColumnName());
        assertEquals("T2", columns.get(1).getTable().getName());
        assertEquals("B", columns.get(1).getColumnName());
        assertEquals("T3", columns.get(2).getTable().getName());
        assertEquals("C", columns.get(2).getColumnName());
    }

    @Test
    public void testNoColumnsInLiteralExpression() throws Exception {
        Expression expr = CCJSqlParserUtil.parseExpression("5 = 5");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertTrue(columns.isEmpty(), "Literal-only expression should yield no columns");
    }

    @Test
    public void testSingleColumnNoTable() throws Exception {
        Expression expr = CCJSqlParserUtil.parseExpression("X = 10");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(1, columns.size());
        assertEquals("X", columns.get(0).getColumnName());
    }

    @Test
    public void testRepeatedColumnExtractedMultipleTimes() throws Exception {
        // A = A (same column appears twice)
        Expression expr = CCJSqlParserUtil.parseExpression("A = A");
        ColumnExtractor extractor = new ColumnExtractor();
        expr.accept(extractor);

        List<Column> columns = extractor.getColumns();
        assertEquals(2, columns.size(), "Same column referenced twice should appear twice");
    }

    @Test
    public void testGetColumnsReturnsEmptyBeforeAccept() {
        ColumnExtractor extractor = new ColumnExtractor();
        List<Column> columns = extractor.getColumns();

        assertTrue(columns.isEmpty(), "Should return empty list before any expression is visited");
    }
}
