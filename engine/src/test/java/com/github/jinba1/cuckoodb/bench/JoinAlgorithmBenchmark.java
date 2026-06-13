package com.github.jinba1.cuckoodb.bench;

import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.PlanContext;
import com.github.jinba1.cuckoodb.QueryConfig;
import com.github.jinba1.cuckoodb.operator.CachedOperator;
import com.github.jinba1.cuckoodb.operator.HashJoinOperator;
import com.github.jinba1.cuckoodb.operator.JoinOperator;
import com.github.jinba1.cuckoodb.operator.Operator;
import com.github.jinba1.cuckoodb.operator.ScanOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Operator-level comparison of nested-loop vs hash join over in-memory children
 * (CachedOperator), isolating the join algorithm from CSV parsing.
 *
 * Run locally (not in CI):
 *   ./mvnw -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
 *     "-Dexec.args=-cp %classpath org.openjdk.jmh.Main JoinAlgorithmBenchmark"
 * (exec:exec with %classpath gives the JMH parent a real java.class.path, so forked
 * benchmark VMs inherit the test classpath; exec:java would break forking.)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class JoinAlgorithmBenchmark {

    // Sizes start at 1000: smaller joins finish in microseconds, below the reliability
    // threshold of Level.Invocation timing (per-invocation setup dominates the bracket)
    @Param({"1000", "5000"})
    public int rowsPerSide;

    @Param({"1", "10"})
    public int matchesPerKey;

    private Path dbDir;
    private Expression joinCondition;
    private PlanContext ctx;
    private CachedOperator cachedOuter;
    private CachedOperator cachedInner;

    @Setup(Level.Trial)
    public void generateData() throws Exception {
        dbDir = Files.createTempDirectory("jmh-join-db");
        Path data = dbDir.resolve("data");
        Files.createDirectories(data);
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        left.add("k,v");
        right.add("k,w");
        int distinctKeys = Math.max(1, rowsPerSide / matchesPerKey);
        for (int i = 0; i < rowsPerSide; i++) {
            left.add((i % distinctKeys) + "," + i);
            right.add((i % distinctKeys) + "," + (i * 7));
        }
        Files.write(data.resolve("L.csv"), left);
        Files.write(data.resolve("R.csv"), right);
        joinCondition = CCJSqlParserUtil.parseCondExpression("L.k = R.k");
    }

    @Setup(Level.Invocation)
    public void rebuildOperators() {
        // Fresh catalog per invocation keeps intermediate-schema registration bounded;
        // CSV parsing happens here, outside the measured methods.
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(dbDir.toString());
        ctx = new PlanContext(QueryConfig.defaults());
        cachedOuter = new CachedOperator(new ScanOperator(ctx, "L"));
        cachedInner = new CachedOperator(new ScanOperator(ctx, "R"));
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        try (var paths = Files.walk(dbDir)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    @Benchmark
    public void nestedLoopJoin(Blackhole bh) {
        drain(new JoinOperator(ctx, cachedOuter, cachedInner, joinCondition), bh);
    }

    @Benchmark
    public void hashJoin(Blackhole bh) {
        drain(new HashJoinOperator(ctx, cachedOuter, cachedInner, joinCondition), bh);
    }

    private static void drain(Operator op, Blackhole bh) {
        var tuple = op.getNextTuple();
        while (tuple != null) {
            bh.consume(tuple);
            tuple = op.getNextTuple();
        }
    }
}
