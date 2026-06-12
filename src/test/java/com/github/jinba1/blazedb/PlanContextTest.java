package com.github.jinba1.blazedb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PlanContextTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    @BeforeEach
    public void setUp() throws IOException {
        DBCatalog.resetDBCatalog();
        Path dataDir = tempDb.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("Student.csv"), "A,B,C\n1,2,3\n4,5,6\n");
        DBCatalog.initDBCatalog(tempDb.toString());
        ctx = new PlanContext(QueryConfig.defaults());
    }

    @Test
    public void holdsItsConfig() {
        assertTrue(ctx.config().useHashJoin());
        PlanContext custom = new PlanContext(new QueryConfig(false, false));
        assertFalse(custom.config().useQueryOptimization());
    }

    @Test
    public void registersIntermediateSchemaWithTempPrefix() {
        Map<String, Integer> schema = new HashMap<>(Map.of("Student.a", 0, "Student.b", 1));
        String id = ctx.registerSchemaWithTransformation(
                schema, "Student", SchemaTransformationType.PROJECTION, new HashMap<>());
        assertTrue(id.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX));
        assertEquals(schema, ctx.getIntermediateSchema(id));
        assertEquals("Student", ctx.getParentSchemaId(id));
    }

    @Test
    public void registrationsAreIsolatedBetweenContexts() {
        Map<String, Integer> schema = new HashMap<>(Map.of("Student.a", 0));
        String id = ctx.registerSchemaWithTransformation(
                schema, "Student", SchemaTransformationType.PROJECTION, new HashMap<>());

        PlanContext other = new PlanContext(QueryConfig.defaults());
        assertNull(other.getIntermediateSchema(id));
        assertNotNull(ctx.getIntermediateSchema(id));
    }

    @Test
    public void resolvesColumnThroughParentChain() {
        // child schema lacks the column; parent (base table) has it
        Map<String, Integer> child = new HashMap<>(Map.of("Student.b", 0));
        String childId = ctx.registerSchemaWithTransformation(
                child, "Student", SchemaTransformationType.PROJECTION, new HashMap<>());
        ctx.addParentSchema(childId, "Student");

        assertEquals(0, ctx.resolveColumnWithOrigins(childId, "Student", "b"));
        // resolution falls through to the parent for columns not in the child schema
        assertNotNull(ctx.resolveColumnWithOrigins(childId, "Student", "a"));
    }

    @Test
    public void multiParentTracking() {
        Map<String, Integer> joined = new HashMap<>(Map.of("Student.a", 0, "Enrolled.x", 1));
        String id = ctx.registerSchemaWithTransformation(
                joined, null, SchemaTransformationType.JOIN, new HashMap<>());
        ctx.addParentSchema(id, "Student");
        ctx.addParentSchema(id, "Enrolled");
        assertEquals(List.of("Student", "Enrolled"), ctx.getAllParentSchemas(id));
    }

    @Test
    public void orderedColumnNamesForBaseAndIntermediateSchemas() {
        // base table: delegates to DBCatalog
        assertEquals(List.of("a", "b", "c"), ctx.getOrderedColumnNames("Student"));

        // intermediate: bare-ifies qualified names, max-index defines width
        Map<String, Integer> schema = new HashMap<>(Map.of("Student.b", 0, "Student.a", 1));
        String id = ctx.registerSchemaWithTransformation(
                schema, "Student", SchemaTransformationType.PROJECTION, new HashMap<>());
        assertEquals(List.of("b", "a"), ctx.getOrderedColumnNames(id));
    }

    @Test
    public void unknownSchemaThrows() {
        assertThrows(QueryExecutionException.class,
                () -> ctx.getOrderedColumnNames("temp_doesnotexist"));
    }

    @Test
    public void orderedColumnNamesStripQualifierAndKeepAggregates() {
        Map<String, Integer> schema = Map.of("student.a", 0, "SUM(student.b)", 1);
        String id = ctx.registerSchemaWithTransformation(
                schema, null, SchemaTransformationType.AGGREGATION, Map.of());
        assertEquals(List.of("a", "sum(student.b)"), ctx.getOrderedColumnNames(id));
    }

    @Test
    public void orderedColumnNamesHandleAliasedJoinSchemas() {
        Map<String, Integer> schema = new HashMap<>();
        schema.put("student.a", 0); schema.put("a", 0);
        schema.put("student.b", 1); schema.put("b", 1);
        schema.put("enrolled.i", 2); schema.put("i", 2);
        String id = ctx.registerSchemaWithTransformation(
                schema, null, SchemaTransformationType.AGGREGATION, Map.of());
        assertEquals(List.of("a", "b", "i"), ctx.getOrderedColumnNames(id));
    }
}
