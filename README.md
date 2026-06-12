# Java Query Engine

![CI](https://github.com/JinBa1/java-query-engine/actions/workflows/ci.yml/badge.svg)
![Coverage](https://codecov.io/gh/JinBa1/java-query-engine/branch/main/graph/badge.svg)
![Dependencies](https://img.shields.io/badge/dependencies-up%20to%20date-brightgreen)

An in-memory relational query engine built on the Volcano/iterator model. Parses SQL via JSqlParser, builds an operator tree, and executes queries tuple-by-tuple against CSV data.

## Architecture

```
SQL → JSqlParser → QueryPlanner → QueryPlanOptimizer → Operator Tree → Results
```

**Core components:**

| Component | Role |
|-----------|------|
| `QueryPlanner` | Parses SQL and builds the operator pipeline |
| `QueryPlanOptimizer` | Selection pushdown, trivial operator removal |
| `DBCatalog` | Schema and table metadata (singleton) |
| `Value` | Typed tuple values (sealed interface: `IntValue`, `StringValue`) |
| `ExpressionEvaluator` | Evaluates WHERE/HAVING conditions per tuple |
| `ExpressionPreprocessor` | Resolves column references to indices |
| `ConditionSplitter` | Separates join predicates from selection predicates |

**Operator hierarchy** (all extend `Operator`):

`ScanOperator` → `SelectOperator` → `ProjectOperator` → `JoinOperator` / `HashJoinOperator` → `SortOperator` → `AggregateOperator` → `DuplicateEliminationOperator` → `LimitOperator`

## Feature Matrix

| Feature | Status |
|---------|--------|
| `SELECT *` / projection | ✅ Supported |
| `WHERE` predicates | ✅ Supported |
| Inner joins (nested-loop) | ✅ Supported |
| Hash join (auto-selected for equi-joins) | ✅ Supported |
| `ORDER BY` | ✅ Supported |
| `GROUP BY` + `SUM`, `COUNT`, `AVG`, `MIN`, `MAX` | ✅ Supported |
| `LIMIT n` | ✅ Supported |
| `DISTINCT` | ✅ Supported |
| Nested arithmetic/comparison expressions | ✅ Supported |
| Query optimisation (selection pushdown) | ✅ Supported |
| Typed columns (int, string) | ✅ Supported |
| CSV header support | ✅ Supported |
| Query budgets (`--max-tuples`, `--timeout-ms`) | ✅ Supported |
| `EXPLAIN` plan inspection | ✅ Supported |
| Indexes | ❌ Not supported |
| Transactions | ❌ Not supported |
| INSERT / UPDATE / DELETE | ❌ Not supported |
| Concurrency | ❌ Not supported |
| Persistence | ❌ Not supported |
| Full SQL dialect | ❌ Not supported |

## Scope

This engine supports **SQL-over-CSV query execution**: read-only queries against tables stored as CSV files. It does not support transactions, indexes, data modification (INSERT/UPDATE/DELETE), concurrency, persistence, or a full SQL dialect. Values are typed int or string, inferred per column from the data. Tables are discovered from CSV files with header rows; no separate schema file.

Supported SQL features include `SELECT`/`FROM`/`WHERE`, `GROUP BY` with `SUM`, `COUNT`, `AVG`, `MIN`, and `MAX` aggregates, `ORDER BY`, `DISTINCT`, inner joins, and `LIMIT n`.

The focus is on demonstrating query planning, optimisation, and the Volcano iterator execution model.

### Aggregate and LIMIT semantics

| Case | Behavior |
|---|---|
| `AVG` of ints | truncated integer division (toward zero) |
| Aggregate over empty input, no `GROUP BY` | zero rows (header only) — deviates from SQL's NULL row |
| `COUNT(col)` | equals `COUNT(*)` — the engine has no NULLs |
| `SUM`/`AVG` on a string column | error |
| `MIN`/`MAX` on strings | lexicographic |
| `SUM` past int range | error |
| `LIMIT 0` | header-only output |
| `OFFSET`, `LIMIT ALL` | not supported (error) |

## Quick Start

**Prerequisites:** Java 17, Maven (or use the included Maven Wrapper).

```bash
# Clone
git clone https://github.com/JinBa1/java-query-engine.git
cd java-query-engine

# Build fat JAR
./mvnw clean compile assembly:single
```

**Run a query:**

```bash
java -cp target/java-query-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.blazedb.BlazeDB \
  database_dir input_file output_file [--max-tuples=N] [--timeout-ms=N]
```

Both `--max-tuples` and `--timeout-ms` are optional and independent. Omit either to impose no limit on that dimension.

### Query budgets

The engine enforces **total-work semantics**: every tuple emitted by any operator in the tree counts against the budget, including intermediate tuples that are later filtered or joined. A cross-product explosion that never produces output rows will still hit the tuple limit. The timeout clock starts lazily at the first tuple emission.

When a budget is exceeded:
- The partial output file is deleted.
- `Error: <message>` is written to stderr.
- The process exits with code 1.

Both flags are optional and independent — you can use one, both, or neither.

### EXPLAIN

Prefix any query with `EXPLAIN` to inspect the query plan without executing it:

```sql
EXPLAIN SELECT Student.B, SUM(Student.C) FROM Student, Enrolled
WHERE Student.D > 30 AND Student.A = Enrolled.A
GROUP BY Student.B;
```

The output file receives a two-section plan:

```
=== Plan (as written) ===
Aggregate[group by: Student.B; calls: SUM(Student.c)]
  Project[Enrolled.A, Student.A, Student.B, Student.C, Student.D]
    Select[Student.D > 30]
      Join[Student.A = Enrolled.A]
        Scan[Student]
        Scan[Enrolled]

=== Plan (optimized) ===
Aggregate[group by: Student.B; calls: SUM(Student.c)]
  Project[Enrolled.A, Student.A, Student.B, Student.C, Student.D]
    Join[Student.A = Enrolled.A]
      Select[Student.D > 30]
        Scan[Student]
      Project[Enrolled.A]
        Scan[Enrolled]
```

No query execution occurs for EXPLAIN queries.

## Join algorithms

The engine supports two join algorithms; the planner selects between them automatically.

### Nested-loop join

`JoinOperator` implements a classic nested-loop join: for every outer tuple the inner child is rewound and scanned in full. It handles any join condition (equality, inequality, arbitrary expression, or cross product with no condition). `EXPLAIN` shows it as `Join[<condition>]`.

### Hash join

`HashJoinOperator` extends `JoinOperator` with an in-memory hash join. The inner (build) side is drained once into a `HashMap` keyed by the equality conjuncts; the outer (probe) side then streams through once. After a hash-table lookup, the full original condition is re-evaluated on every candidate, so residual non-equality conjuncts (e.g. `A.x = B.x AND A.y > 3`) work correctly. Output order — outer-major, inner order preserved within each key bucket — is identical to the nested-loop join. `EXPLAIN` shows it as `HashJoin[<condition>]`.

**Auto-selection rule:** the planner chooses hash join when `Constants.useHashJoin` is `true` (the default) **and** the join condition contains at least one column-to-column equality conjunct (e.g. `Student.A = Enrolled.A`). Cross products (no condition) and pure non-equi joins (e.g. `A.x > B.y` only) always use nested-loop join.

**Toggle:** set `Constants.useHashJoin = false` at program start (or in tests) to force nested-loop for all joins.

### Benchmarks

Performance was measured with a JMH 1.37 benchmark suite in the `bench/` package (`src/test/java/com/github/jinba1/blazedb/bench/`). The suite is compiled in CI but never run there; run it locally with:

```bash
./mvnw -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
  "-Dexec.args=-cp %classpath org.openjdk.jmh.Main .*Benchmark"
```

**Results** (OpenJDK 21.0.5, Intel Core i9-13900HX, 32 logical cores, Linux under WSL2):

| Benchmark | matchesPerKey | rowsPerSide | useHashJoin | Mode | Cnt | Score | Error | Units |
|-----------|--------------|-------------|-------------|------|-----|-------|-------|-------|
| EndToEndJoinBenchmark.planAndDrain | N/A | N/A | true | avgt | 3 | 1.028 | ± 0.288 | ms/op |
| EndToEndJoinBenchmark.planAndDrain | N/A | N/A | false | avgt | 3 | 315.523 | ± 36.021 | ms/op |
| JoinAlgorithmBenchmark.hashJoin | 1 | 1000 | N/A | avgt | 5 | 0.270 | ± 0.011 | ms/op |
| JoinAlgorithmBenchmark.hashJoin | 1 | 5000 | N/A | avgt | 5 | 1.382 | ± 0.154 | ms/op |
| JoinAlgorithmBenchmark.hashJoin | 10 | 1000 | N/A | avgt | 5 | 2.160 | ± 0.109 | ms/op |
| JoinAlgorithmBenchmark.hashJoin | 10 | 5000 | N/A | avgt | 5 | 10.661 | ± 0.840 | ms/op |
| JoinAlgorithmBenchmark.nestedLoopJoin | 1 | 1000 | N/A | avgt | 5 | 202.621 | ± 25.313 | ms/op |
| JoinAlgorithmBenchmark.nestedLoopJoin | 1 | 5000 | N/A | avgt | 5 | 5027.912 | ± 370.564 | ms/op |
| JoinAlgorithmBenchmark.nestedLoopJoin | 10 | 1000 | N/A | avgt | 5 | 194.786 | ± 4.916 | ms/op |
| JoinAlgorithmBenchmark.nestedLoopJoin | 10 | 5000 | N/A | avgt | 5 | 4785.620 | ± 212.683 | ms/op |

`EndToEndJoinBenchmark` joins two 1 000-row CSV tables through the full planner pipeline; nested-loop re-parses the inner CSV once per outer row, so the gap (≈ 307×) reflects both the algorithmic difference and I/O cost. `JoinAlgorithmBenchmark` uses in-memory `CachedOperator` inputs to isolate the join algorithm itself; at 5 000 rows/side the operator-level gap is ≈ 3 600×.

Benchmarks are compiled in CI but never executed there.

## Demo

**Input table** (`samples/db/data/Student.csv`):

```
A, B, C, D
1, 200, 50, 33
2, 200, 200, 44
3, 100, 105, 44
4, 100, 50, 11
5, 100, 500, 22
6, 300, 400, 11
```

**Query** (`samples/input/query4.sql`):

```sql
SELECT * FROM Student WHERE Student.A < 3;
```

**Command:**

```bash
java -cp target/java-query-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.blazedb.BlazeDB \
  samples/db samples/input/query4.sql output.csv
```

To limit resource usage, add optional budget flags:

```bash
java -cp target/java-query-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.blazedb.BlazeDB \
  samples/db samples/input/query4.sql output.csv --max-tuples=10000 --timeout-ms=5000
```

**Output** (`output.csv`):

```
a,b,c,d
1,200,50,33
2,200,200,44
```

## Running Examples

The `samples/` directory ships with 20 queries and a small dataset (Student, Course, Enrolled, Staff tables). Expected output lives in `samples/expected_output/`.

```bash
# Run all sample queries and diff against expected output
for i in $(seq 1 20); do
  java -cp target/java-query-engine-1.0.0-jar-with-dependencies.jar \
    com.github.jinba1.blazedb.BlazeDB \
    samples/db "samples/input/query${i}.sql" "/tmp/out${i}.csv"
  diff "samples/expected_output/query${i}.csv" "/tmp/out${i}.csv" && echo "query${i}: OK"
done
```

## Testing

```bash
./mvnw test
```

The test suite covers individual operators, the query planner, the optimiser, expression evaluation, query budgets, EXPLAIN, hash join, and end-to-end integration scenarios (339 tests).

## Project Structure

```
├── src/main/java/com/github/jinba1/blazedb/   # Core engine (35 files)
│   └── operator/                                # Volcano operators (11 files, incl. HashJoinOperator)
├── src/test/java/com/github/jinba1/blazedb/    # JUnit 5 tests (339 tests across 33 files)
├── samples/
│   ├── db/data/                                 # CSV data files (header row + data rows)
│   ├── input/query[1-20].sql                    # Sample queries
│   └── expected_output/query[1-20].csv          # Expected results
├── pom.xml                                      # Maven config (Java 17, JSqlParser 4.7, commons-csv 1.14.1, JMH 1.37 test-scope)
├── mvnw / mvnw.cmd                              # Maven Wrapper
└── LICENSE
```

## Background

Originally built as a university project for the Advanced Database Systems course at the University of Edinburgh, subsequently extended with additional query optimisation and expanded test coverage.

## License

This project is released under the MIT License. See [LICENSE](LICENSE) for details.
