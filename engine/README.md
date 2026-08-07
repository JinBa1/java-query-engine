# cuckooDB — query engine

The query engine under the [cuckooDB gateway](../README.md): an in-memory relational query engine on the Volcano/iterator model. It parses SQL via JSqlParser, builds an operator tree, optimises it, and executes tuple-by-tuple against CSV files. Pure Java 17, **zero Spring dependencies**.

## Architecture

```
SQL → JSqlParser → QueryPlanner → QueryPlanOptimizer → Operator Tree → Results
```

| Component | Role |
|-----------|------|
| `QueryPlanner` | Parses SQL and builds the operator pipeline |
| `QueryPlanOptimizer` | Selection pushdown, projection pushdown, trivial operator removal, consecutive-select merging |
| `DBCatalog` | Schema and table metadata (singleton) |
| `Value` | Typed tuple values (sealed interface: `IntValue`, `StringValue`) |
| `ExpressionEvaluator` | Evaluates WHERE/HAVING conditions per tuple |
| `ExpressionPreprocessor` | Resolves column references to indices |
| `ConditionSplitter` | Separates join predicates from selection predicates |

**Operator hierarchy** (all extend `Operator`):

`ScanOperator` → `SelectOperator` → `ProjectOperator` → `JoinOperator` / `HashJoinOperator` → `SortOperator` → `AggregateOperator` → `DuplicateEliminationOperator` → `LimitOperator`

## Scope

Read-only SQL-over-CSV: `SELECT`/`FROM`/`WHERE`, inner joins, `GROUP BY` with `SUM`/`COUNT`/`AVG`/`MIN`/`MAX`, `ORDER BY`, `DISTINCT`, `LIMIT n`, and nested arithmetic/comparison expressions. Values are typed int or string, inferred per column from the data. Tables are discovered from CSV files with header rows; no separate schema file. No transactions, indexes, data modification, persistence, or full SQL dialect — the focus is query planning, optimisation, and the Volcano execution model.

## Build and run (CLI)

Run from the repository root (uses the Maven Wrapper):

```bash
./mvnw -pl engine -DskipTests clean package

java -cp engine/target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.cuckoodb.CuckooDB \
  <database_dir> <input_file> <output_file> [--max-tuples=N] [--timeout-ms=N]
```

`<database_dir>` is a directory containing a `data/` subdir of `.csv` tables. `--max-tuples` and `--timeout-ms` are optional and independent — use one, both, or neither.

### Demo

**Input** (`engine/samples/db/data/Student.csv`):

```
A, B, C, D
1, 200, 50, 33
2, 200, 200, 44
3, 100, 105, 44
4, 100, 50, 11
5, 100, 500, 22
6, 300, 400, 11
```

**Command** (`engine/samples/input/query4.sql` is `SELECT * FROM Student WHERE Student.A < 3;`):

```bash
java -cp engine/target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.cuckoodb.CuckooDB \
  engine/samples/db engine/samples/input/query4.sql output.csv
```

**Output** (`output.csv`):

```
a,b,c,d
1,200,50,33
2,200,200,44
```

## Query budgets

The engine enforces **total-work semantics**: every tuple emitted by any operator counts against the budget, including intermediate tuples later filtered or joined. A cross-product explosion that never produces output rows still hits the tuple limit. The timeout clock starts lazily at the first tuple emission. On a breach the partial output file is deleted, `Error: <message>` is written to stderr, and the process exits 1.

## EXPLAIN

Prefix any query with `EXPLAIN` to inspect the plan without executing it. The output has two sections — as written, then after optimisation:

```
=== Plan (as written) ===
Aggregate[group by: Student.B; calls: SUM(Student.c)]
  Project[Enrolled.A, Student.A, Student.B, Student.C, Student.D]
    Select[Student.D > 30]
      HashJoin[Student.A = Enrolled.A]
        Scan[Student]
        Scan[Enrolled]

=== Plan (optimized) ===
Aggregate[group by: Student.B; calls: SUM(Student.c)]
  Project[Enrolled.A, Student.A, Student.B, Student.C, Student.D]
    HashJoin[Student.A = Enrolled.A]
      Select[Student.D > 30]
        Scan[Student]
      Project[Enrolled.A]
        Scan[Enrolled]
```

The optimiser pushes the `Select` below the join (selection pushdown) and inserts a projection on the inner scan; the planner picked a hash join for the equi-condition. No execution occurs for `EXPLAIN`.

## Join algorithms

The planner selects between two join algorithms automatically.

**Nested-loop** (`JoinOperator`): for every outer tuple the inner child is rewound and scanned in full. Handles any condition — equality, inequality, arbitrary expression, or cross product. Shown in `EXPLAIN` as `Join[<condition>]`.

**Hash** (`HashJoinOperator extends JoinOperator`): the inner (build) side is drained once into a `HashMap` keyed by the equality conjuncts; the outer (probe) side streams through once. After a lookup, the full original condition is re-evaluated on every candidate, so residual non-equality conjuncts (e.g. `A.x = B.x AND A.y > 3`) work. Output order is identical to nested-loop (outer-major, inner order preserved per key bucket). Shown as `HashJoin[<condition>]`.

**Auto-selection:** hash join is used when the per-query `QueryConfig.useHashJoin` flag is `true` (the production default) **and** the condition has at least one column-to-column equality conjunct. Cross products and pure non-equi joins always use nested-loop. Tests and benchmarks can pass `new QueryConfig(true, false)` to `QueryPlanner` to force nested-loop everywhere without mutating global state.

### Benchmarks

A JMH 1.37 suite lives in `engine/src/test/java/com/github/jinba1/cuckoodb/bench/`. It is compiled in CI but never run there; run it locally from the repository root:

```bash
./mvnw -pl engine -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
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

`EndToEndJoinBenchmark` joins two 1 000-row CSV tables through the full pipeline; nested-loop re-parses the inner CSV once per outer row, so the ≈ 307× gap reflects both algorithm and I/O. `JoinAlgorithmBenchmark` uses in-memory inputs to isolate the algorithm; at 5 000 rows/side the operator-level gap is ≈ 3 600×.

## Sample queries

`engine/samples/` ships 20 queries and a small dataset (Student, Course, Enrolled, Staff). The bundled runner diffs each result against `engine/samples/expected_output/` — the golden-output regression gate. Launch via `exec:exec` (not `exec:java`) so it runs with the engine module as the working directory:

```bash
./mvnw -pl engine -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
  "-Dexec.args=-cp %classpath com.github.jinba1.cuckoodb.SampleQueryRunner"
```

## Testing

```bash
./mvnw -pl engine test
```

419 tests across operators, the planner, the optimiser, expression evaluation, query budgets, EXPLAIN, hash join, and end-to-end integration scenarios.

## Layout

```
engine/
├── src/main/java/com/github/jinba1/cuckoodb/   # core engine (45 files)
│   └── operator/                                # Volcano operators (11 files, incl. HashJoinOperator)
├── src/test/java/com/github/jinba1/cuckoodb/    # JUnit 5 tests (419 across 41 files)
└── samples/
    ├── db/data/                                 # CSV tables (header row + data rows)
    ├── input/query[1-20].sql
    └── expected_output/query[1-20].csv
```
