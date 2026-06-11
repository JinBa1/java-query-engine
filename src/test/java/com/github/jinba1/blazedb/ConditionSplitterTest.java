package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ConditionSplitterTest {

    private static final String TEST_DB_DIR = "src/test/resources/testdb_splitter";
    private static final String SCHEMA_FILE = TEST_DB_DIR + "/schema.txt";
    private static final String DATA_DIR = TEST_DB_DIR + "/data";

    private static final String OUTER_TABLE = "Student";
    private static final String INNER_TABLE = "Course";

    private String outerSchemaId;
    private String innerSchemaId;

    @BeforeEach
    public void setUp() throws IOException {
        Files.createDirectories(Paths.get(DATA_DIR));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SCHEMA_FILE))) {
            writer.write(OUTER_TABLE + " sid name age gpa\n");
            writer.write(INNER_TABLE + " cid sid grade\n");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + OUTER_TABLE + ".csv"))) {
            writer.write("sid, name, age, gpa\n");
            writer.write("1, 25, 85, 3\n");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + INNER_TABLE + ".csv"))) {
            writer.write("cid, sid, grade\n");
            writer.write("10, 1, 90\n");
        }

        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(TEST_DB_DIR);

        Map<String, Integer> outerSchema = new HashMap<>();
        outerSchema.put("Student.sid", 0);
        outerSchema.put("Student.name", 1);
        outerSchema.put("Student.age", 2);
        outerSchema.put("Student.gpa", 3);

        Map<String, Integer> innerSchema = new HashMap<>();
        innerSchema.put("Course.cid", 0);
        innerSchema.put("Course.sid", 1);
        innerSchema.put("Course.grade", 2);

        Map<String, String> outerDetails = new HashMap<>();
        outerDetails.put("Student.sid", "Student.sid");
        outerDetails.put("Student.name", "Student.name");
        outerDetails.put("Student.age", "Student.age");
        outerDetails.put("Student.gpa", "Student.gpa");

        Map<String, String> innerDetails = new HashMap<>();
        innerDetails.put("Course.cid", "Course.cid");
        innerDetails.put("Course.sid", "Course.sid");
        innerDetails.put("Course.grade", "Course.grade");

        outerSchemaId = DBCatalog.getInstance().registerSchemaWithTransformation(
                outerSchema, OUTER_TABLE, SchemaTransformationType.PROJECTION, outerDetails);
        innerSchemaId = DBCatalog.getInstance().registerSchemaWithTransformation(
                innerSchema, INNER_TABLE, SchemaTransformationType.PROJECTION, innerDetails);
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + OUTER_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + INNER_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(SCHEMA_FILE));
        Files.deleteIfExists(Paths.get(DATA_DIR));
        Files.deleteIfExists(Paths.get(TEST_DB_DIR));
    }

    @Test
    public void testPureOuterSelection() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression("Student.age > 20");
        expr.accept(splitter);

        assertNotNull(splitter.getOuterCondition(), "Single outer-table condition → outer");
        assertNull(splitter.getInnerCondition());
        assertNull(splitter.getJoinCondition());
    }

    @Test
    public void testPureInnerSelection() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression("Course.grade >= 80");
        expr.accept(splitter);

        assertNull(splitter.getOuterCondition());
        assertNotNull(splitter.getInnerCondition(), "Single inner-table condition → inner");
        assertNull(splitter.getJoinCondition());
    }

    @Test
    public void testJoinCondition() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression("Student.sid = Course.sid");
        expr.accept(splitter);

        assertNull(splitter.getOuterCondition());
        assertNull(splitter.getInnerCondition());
        assertNotNull(splitter.getJoinCondition(), "Cross-table predicate → join");
    }

    @Test
    public void testMixedAndExpression() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression(
                "Student.sid = Course.sid AND Student.age > 20 AND Course.grade >= 80");
        expr.accept(splitter);

        assertNotNull(splitter.getOuterCondition(), "Student.age > 20 → outer");
        assertNotNull(splitter.getInnerCondition(), "Course.grade >= 80 → inner");
        assertNotNull(splitter.getJoinCondition(), "Student.sid = Course.sid → join");
    }

    @Test
    public void testNoConditions() {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);

        assertNull(splitter.getOuterCondition());
        assertNull(splitter.getInnerCondition());
        assertNull(splitter.getJoinCondition());
    }

    @Test
    public void testMultipleOuterSelections() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression(
                "Student.age > 20 AND Student.gpa >= 3");
        expr.accept(splitter);

        assertNotNull(splitter.getOuterCondition());
        assertNull(splitter.getInnerCondition());
        assertNull(splitter.getJoinCondition());

        String combined = splitter.getOuterCondition().toString();
        assertTrue(combined.contains("AND"));
    }

    @Test
    public void testComplexNestedAnd() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression(
                "Student.sid = Course.sid AND Student.age > 20 AND Course.grade >= 80 AND Student.gpa >= 3");
        expr.accept(splitter);

        assertNotNull(splitter.getOuterCondition());
        assertNotNull(splitter.getInnerCondition());
        assertNotNull(splitter.getJoinCondition());

        String outerStr = splitter.getOuterCondition().toString();
        assertTrue(outerStr.contains("age") || outerStr.contains("gpa"),
                "Outer condition should reference age or gpa");
    }

    @Test
    public void testNotEqualsJoinCondition() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression("Student.sid != Course.sid");
        expr.accept(splitter);

        assertNull(splitter.getOuterCondition());
        assertNull(splitter.getInnerCondition());
        assertNotNull(splitter.getJoinCondition(), "!= across tables → join");
    }

    @Test
    public void testGreaterThanJoinCondition() throws Exception {
        ConditionSplitter splitter = new ConditionSplitter(outerSchemaId, innerSchemaId);
        Expression expr = CCJSqlParserUtil.parseExpression("Student.sid > Course.cid");
        expr.accept(splitter);

        assertNotNull(splitter.getJoinCondition(), "> across tables → join");
        assertNull(splitter.getOuterCondition());
        assertNull(splitter.getInnerCondition());
    }
}
