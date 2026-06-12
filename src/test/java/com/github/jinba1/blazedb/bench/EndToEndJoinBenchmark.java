package com.github.jinba1.blazedb.bench;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.QueryConfig;
import com.github.jinba1.blazedb.QueryPlanner;
import com.github.jinba1.blazedb.operator.Operator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end planner + ScanOperator pipeline comparison. With nested-loop join the inner
 * CSV is re-parsed once per outer row; hash join reads it once — this benchmark shows the
 * real-world gap, not just the algorithmic one.
 *
 * Run locally (not in CI):
 *   ./mvnw -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
 *     "-Dexec.args=-cp %classpath org.openjdk.jmh.Main EndToEndJoinBenchmark"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
public class EndToEndJoinBenchmark {

    @Param({"true", "false"})
    public boolean useHashJoin;

    private static final int ROWS = 1000;

    private Path dbDir;
    private Path queryFile;

    @Setup(Level.Trial)
    public void generateData() throws IOException {
        dbDir = Files.createTempDirectory("jmh-e2e-db");
        Path data = dbDir.resolve("data");
        Files.createDirectories(data);
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        a.add("k,v");
        b.add("k,w");
        for (int i = 0; i < ROWS; i++) {
            a.add(i + "," + i);
            b.add(i + "," + (i * 7));
        }
        Files.write(data.resolve("A.csv"), a);
        Files.write(data.resolve("B.csv"), b);
        queryFile = dbDir.resolve("q.sql");
        Files.writeString(queryFile, "SELECT * FROM A, B WHERE A.k = B.k;");
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        try (var paths = Files.walk(dbDir)) {
            paths.sorted((x, y) -> y.compareTo(x)).forEach(p -> p.toFile().delete());
        }
    }

    @Benchmark
    public void planAndDrain(Blackhole bh) {
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(dbDir.toString());
        Operator root = QueryPlanner.parseStatement(queryFile.toString(), new QueryConfig(true, useHashJoin));
        var tuple = root.getNextTuple();
        while (tuple != null) {
            bh.consume(tuple);
            tuple = root.getNextTuple();
        }
    }
}
