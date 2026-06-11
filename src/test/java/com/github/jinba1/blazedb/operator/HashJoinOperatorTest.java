package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.QueryBudget;
import com.github.jinba1.blazedb.QueryExecutionException;
import com.github.jinba1.blazedb.Tuple;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HashJoinOperator semantics are pinned against JoinOperator (nested loop):
 * for the same children and condition, both must produce the exact same tuple
 * sequence — including order (outer-major, inner order within key).
 */
public class HashJoinOperatorTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        // L: int key with duplicates, int payload
        Files.write(data.resolve("L.csv"), List.of("k,v", "1,10", "2,20", "2,21", "3,30", "5,50"));
        // R: int key with duplicates, int payload
        Files.write(data.resolve("R.csv"), List.of("k,w", "2,200", "1,100", "2,201", "4,400"));
        // S/T: string keys
        Files.write(data.resolve("S.csv"), List.of("name,x", "alice,1", "bob,2", "alice,3"));
        Files.write(data.resolve("T.csv"), List.of("name,y", "alice,7", "carol,8"));
        // M: string key column named like L's int key (for the type-mismatch test)
        Files.write(data.resolve("M.csv"), List.of("k,z", "one,1", "two,2"));
        // E: header-only empty table, same shape as R
        Files.write(data.resolve("E.csv"), List.of("k,w"));
        initCatalog();
    }

    private void initCatalog() {
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
    }

    private static Expression cond(String s) throws Exception {
        return CCJSqlParserUtil.parseCondExpression(s);
    }

    private static List<String> drain(Operator op) {
        List<String> rows = new ArrayList<>();
        Tuple t;
        while ((t = op.getNextTuple()) != null) {
            rows.add(t.getTuple().toString());
        }
        return rows;
    }

    /** Same join through both algorithms; sequences must match exactly. Returns the rows. */
    private List<String> assertEquivalent(String outer, String inner, String condition) throws Exception {
        initCatalog();
        List<String> nlj = drain(new JoinOperator(
                new ScanOperator(outer), new ScanOperator(inner), cond(condition)));
        initCatalog();
        List<String> hash = drain(new HashJoinOperator(
                new ScanOperator(outer), new ScanOperator(inner), cond(condition)));
        assertEquals(nlj, hash, "hash join must replicate nested-loop output exactly");
        return nlj;
    }

    @Test
    public void pureEquiJoinWithDuplicateKeys() throws Exception {
        List<String> rows = assertEquivalent("L", "R", "L.k = R.k");
        assertEquals(5, rows.size()); // 1->1 match, 2x2 -> 4 matches
    }

    @Test
    public void equiPlusResidualConjunct() throws Exception {
        List<String> rows = assertEquivalent("L", "R", "L.k = R.k AND L.v > 15");
        assertEquals(4, rows.size()); // drops L row (1,10)
    }

    @Test
    public void multipleEquiConjuncts() throws Exception {
        assertEquivalent("L", "R", "L.k = R.k AND L.v = R.w"); // no L.v equals any R.w -> empty
    }

    @Test
    public void reversedConditionSides() throws Exception {
        List<String> rows = assertEquivalent("L", "R", "R.k = L.k");
        assertEquals(5, rows.size());
    }

    @Test
    public void stringKeys() throws Exception {
        List<String> rows = assertEquivalent("S", "T", "S.name = T.name");
        assertEquals(2, rows.size()); // alice x2
    }

    @Test
    public void emptyInnerSide() throws Exception {
        assertEquals(0, assertEquivalent("L", "E", "L.k = E.k").size());
    }

    @Test
    public void emptyOuterSide() throws Exception {
        assertEquals(0, assertEquivalent("E", "R", "E.k = R.k").size());
    }

    @Test
    public void resetReplaysIdentically() throws Exception {
        HashJoinOperator join = new HashJoinOperator(
                new ScanOperator("L"), new ScanOperator("R"), cond("L.k = R.k"));
        List<String> first = drain(join);
        join.reset();
        assertEquals(first, drain(join));
    }

    @Test
    public void hashJoinChargesLessTotalWorkThanNestedLoop() throws Exception {
        initCatalog();
        JoinOperator nlj = new JoinOperator(
                new ScanOperator("L"), new ScanOperator("R"), cond("L.k = R.k"));
        QueryBudget nljBudget = new QueryBudget(null, null);
        nlj.attachBudget(nljBudget);
        drain(nlj);

        initCatalog();
        HashJoinOperator hash = new HashJoinOperator(
                new ScanOperator("L"), new ScanOperator("R"), cond("L.k = R.k"));
        QueryBudget hashBudget = new QueryBudget(null, null);
        hash.attachBudget(hashBudget);
        drain(hash);

        assertTrue(hashBudget.processed() < nljBudget.processed(),
                "hash=" + hashBudget.processed() + " nlj=" + nljBudget.processed());
    }

    @Test
    public void nonEquiConditionFailsLoudly() throws Exception {
        HashJoinOperator join = new HashJoinOperator(
                new ScanOperator("L"), new ScanOperator("R"), cond("L.v > R.w"));
        QueryExecutionException ex = assertThrows(QueryExecutionException.class, join::getNextTuple);
        assertTrue(ex.getMessage().contains("equality"), ex.getMessage());
    }

    @Test
    public void crossTypeJoinKeyFailsLoudly() throws Exception {
        // L.k is INT, M.k is STRING — nested loop throws on evaluation;
        // hash must be equally loud, not silently empty
        HashJoinOperator join = new HashJoinOperator(
                new ScanOperator("L"), new ScanOperator("M"), cond("L.k = M.k"));
        QueryExecutionException ex = assertThrows(QueryExecutionException.class, () -> drain(join));
        assertTrue(ex.getMessage().toLowerCase().contains("type"), ex.getMessage());
    }

    @Test
    public void describeShowsAlgorithm() throws Exception {
        HashJoinOperator join = new HashJoinOperator(
                new ScanOperator("L"), new ScanOperator("R"), cond("L.k = R.k"));
        assertEquals("HashJoin[L.k = R.k]", join.describe());
    }

    @Test
    public void equiConjunctDetection() throws Exception {
        assertTrue(HashJoinOperator.hasEquiConjunct(cond("L.k = R.k")));
        assertTrue(HashJoinOperator.hasEquiConjunct(cond("L.v > 5 AND L.k = R.k")));
        assertFalse(HashJoinOperator.hasEquiConjunct(cond("L.v > R.w")));
        assertFalse(HashJoinOperator.hasEquiConjunct(cond("L.k = 5"))); // column = literal
        assertFalse(HashJoinOperator.hasEquiConjunct(null)); // cross product
    }
}
