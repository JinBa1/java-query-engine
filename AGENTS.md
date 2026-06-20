# PROJECT KNOWLEDGE BASE

**Generated:** 2026-06-12
**Commit:** 158fe91
**Branch:** feat/sql-aggregates-limit

## OVERVIEW

CuckooDB (engine module artifactId: `cuckoodb-engine`; parent: `cuckoodb-parent`) is an in-memory relational query engine built on the Volcano/iterator model. It parses SQL via JSqlParser, builds an operator tree, and executes tuple-at-a-time over CSV data. The project targets Java 17 and uses JUnit Jupiter 5.10.2, JSqlParser 4.7, and JaCoCo 0.8.12.

## STRUCTURE

```
cuckoodb-parent/                       # repo root (git slug: java-query-engine)
├── pom.xml                            # Parent POM: packaging=pom, modules engine+server, dependency/plugin management (Java 17)
├── .gitignore                         # ignores target/, *.class, .iml, IDE metadata, engine/ test-output dirs
├── .github/workflows/ci.yml           # push/PR to main: ./mvnw clean compile + test + Codecov (engine/target/site/jacoco/jacoco.xml)
├── mvnw / mvnw.cmd / .mvn/            # Maven Wrapper (shared, at root)
├── engine/                            # cuckoodb-engine — pure query engine, ZERO Spring deps
│   ├── pom.xml                        # JSqlParser 4.7, commons-csv 1.14.1, JUnit 5.10.2, JMH 1.37 (test); exec + assembly + jacoco
│   ├── samples/                       # 20-query golden dataset (moved here from repo root in the module split)
│   │   ├── db/data/                   # CSV data files (header row + data rows)
│   │   ├── input/query[1-20].sql      # 20 sample queries
│   │   └── expected_output/query[1-20].csv   # expected results
│   └── src/
│       ├── main/java/com/github/jinba1/cuckoodb/   # 35 core files (CuckooDB entry, planner, optimizer, catalog, budgets, Value/Tuple)
│       │   └── operator/                           # 11 Volcano operators (base + Scan/Select/Project/Join/HashJoin/Sort/Aggregate/Limit/DuplicateElimination + Accumulator)
│       └── test/java/com/github/jinba1/cuckoodb/   # 33 test files (339 tests)
│           ├── CuckooDBTest.java                   # incl. testAllSampleQueries — the 20-sample byte-identical gate
│           ├── ConcurrentQueryExecutionTest.java, DBCatalogTest.java, ...   # planner / optimizer / budget / EXPLAIN / end-to-end
│           ├── operator/                           # operator-level tests + CachedOperator test utility
│           └── bench/                              # JMH benchmarks (compiled in CI, never run there): EndToEndJoinBenchmark, JoinAlgorithmBenchmark
└── server/                            # cuckoodb-server — Spring Boot 4 REST + MCP gateway over the engine
    ├── pom.xml                        # depends on cuckoodb-engine; Spring Boot 4.0.7 (web MVC), springdoc/OpenAPI, Spring AI 2.0.0 MCP server
    └── src/main/java/com/github/jinba1/cuckoodb/server/   # web/ controllers + GlobalExceptionHandler, query/ QueryService+budget+concurrency, catalog/ CatalogFacade, mcp/ CuckooMcpTools (5 @McpTool tools over the QueryService choke point) + TableNameValidator/CatalogMapper, audit/ sink, config/ (90 server tests)
```

Per-file responsibilities are in the WHERE TO LOOK table below.

## WHERE TO LOOK

| File | Package | Notes |
|------|---------|-------|
| `CuckooDB.java` | `com.github.jinba1.cuckoodb` | Entry point. `public static void main(String[] args)` takes db-dir, input SQL file, output CSV path, and optional `--max-tuples=N` / `--timeout-ms=N` flags. `static int run(String[] args)` is main minus System.exit (used in tests). Parses via `QueryPlanner.planQuery`, attaches a `QueryBudget` when flags are present, executes via `execute()`, and writes RFC 4180 output (LF line endings). On budget exceeded: deletes partial output, writes `Error: <message>` to stderr, exits 1. |
| `QueryPlanner.java` | `com.github.jinba1.cuckoodb` | Translates a SQL file into a `PlannedQuery` via `planQuery(filename)`. For EXPLAIN-prefixed queries, renders before/after operator trees via `PlanPrinter`; returned root is a no-op for EXPLAIN. Builds the scan/join/select/project/group-by/sort/distinct/limit pipeline. Auto-selects `HashJoinOperator` when `Constants.useHashJoin` is true and the join condition contains a column=column equality conjunct; falls back to `JoinOperator` otherwise. |
| `QueryBudget.java` | `com.github.jinba1.cuckoodb` | Holds per-query kill limits: `QueryBudget(Long maxTuples, Long timeoutMs)` — both nullable (no limit on that axis). `charge()` increments the tuple counter and checks the wall-clock timeout (lazy start at first call); throws `QueryBudgetExceededException` when either limit is exceeded. `processed()` returns the total tuple count so far. |
| `QueryBudgetExceededException.java` | `com.github.jinba1.cuckoodb` | Unchecked exception thrown by `QueryBudget.charge()` when either the tuple limit or timeout is exceeded. Carries a human-readable message stating which limit was hit. |
| `PlannedQuery.java` | `com.github.jinba1.cuckoodb` | Record: `PlannedQuery(Operator root, String explainText)`. `explainText` is non-null only for EXPLAIN queries; `root` is a no-op for EXPLAIN queries. |
| `PlanPrinter.java` | `com.github.jinba1.cuckoodb` | Utility: `static String print(Operator root)` walks the operator tree depth-first and renders it as an indented text plan using each operator's `describe()` line. |
| `QueryPlanOptimizer.java` | `com.github.jinba1.cuckoodb` | Optimization passes: selection pushdown, trivial project/select removal, consecutive-select merging, projection pushdown. Toggled by Constants.useQueryOptimization. |
| `DBCatalog.java` | `com.github.jinba1.cuckoodb` | Mutable singleton: CSV-header table discovery + INT/STRING type inference at init. Table-to-path map (dbLocations), table schemas (dbSchemata), column types (dbColumnTypes). initDBCatalog(dir) / resetDBCatalog(); getColumnTypes(), getOrderedColumnNames(). No schema.txt. |
| `ExpressionEvaluator.java` | `com.github.jinba1.cuckoodb` | JSqlParser visitor evaluating WHERE/HAVING expressions against a Tuple (boolean and Value evaluation). Type-checked comparisons; string literals via StringValue. |
| `ExpressionPreprocessor.java` | `com.github.jinba1.cuckoodb` | JSqlParser visitor that separates two-table join predicates from single-table selection predicates during planning. |
| `ConditionSplitter.java` | `com.github.jinba1.cuckoodb` | Splits a join condition into outer-only, inner-only, and true join predicate parts (used by optimizer pushdown). Uses Constants.INTERMEDIATE_SCHEMA_PREFIX. |
| `ColumnExtractor.java` | `com.github.jinba1.cuckoodb` | Visitor that collects Column references from an expression. |
| `ColumnIdentity.java` | `com.github.jinba1.cuckoodb` | Value object wrapping a Column with equality/hashCode by table+column name; includes a column-deduplication utility. |
| `Tuple.java` | `com.github.jinba1.cuckoodb` | Row of typed values: wraps List<Value>; getAttribute(i) returns Value, toString() (comma-space separated), equals/hashCode. |
| `TupleComparator.java` | `com.github.jinba1.cuckoodb` | Comparator<Tuple> for multi-column lexicographic sorting by column indices. |
| `SchemaTransformationType.java` | `com.github.jinba1.cuckoodb` | Enum marking the kind of schema transformation an operator performs. |
| `Constants.java` | `com.github.jinba1.cuckoodb` | App constants: useQueryOptimization (boolean, default true), useHashJoin (boolean, default true — set false to force nested-loop for all joins), INTERMEDIATE_SCHEMA_PREFIX = "temp_", DATA_DIRECTORY_NAME = "data". |
| `AggregateFunction.java` | `com.github.jinba1.cuckoodb` | Enum of supported aggregate functions: SUM, COUNT, AVG, MIN, MAX. `fromFunctionName(String)` maps SQL function names (case-insensitive) to enum values; returns null for unrecognised names. |
| `AggregateCall.java` | `com.github.jinba1.cuckoodb` | Record holding one parsed aggregate call from the SELECT list: `function` (AggregateFunction), `argument` (JSqlParser Expression; null for COUNT(*)), and `schemaKey` (the output column name as registered). |
| `SampleQueryRunner.java` | `com.github.jinba1.cuckoodb` | Standalone main that runs all 20 sample queries against samples/db and diffs each output against samples/expected_output/, reporting pass/fail. |
| `Value.java` | `com.github.jinba1.cuckoodb` | Sealed interface for typed tuple values; permits IntValue, StringValue; extends Comparable<Value>; declares typeName(). |
| `IntValue.java` | `com.github.jinba1.cuckoodb` | Record implementing Value; wraps int v(); compareTo orders numerically. |
| `StringValue.java` | `com.github.jinba1.cuckoodb` | Record implementing Value; wraps String v(); compareTo orders lexicographically. |
| `ColumnType.java` | `com.github.jinba1.cuckoodb` | Enum: INT, STRING. Used by DBCatalog and ScanOperator. |
| `QueryExecutionException.java` | `com.github.jinba1.cuckoodb` | Unchecked exception for data/type errors at runtime; messages state operation, column/literal, and both types for agent-legible diagnostics. |
| `Operator.java` | `com.github.jinba1.cuckoodb.operator` | Abstract base for all Volcano operators. Defines `getNextTuple()`, `reset()`, `describe()`, schema methods (`propagateSchemaId`, `registerSchema`, `ensureSchemaRegistered`, `updateSchema`). Holds `protected Operator child`, a schemaRegistered flag, `protected long tupleCounter` (benchmarking via `getTupleCount()`/`resetTupleCount()`). Budget methods: `attachBudget(QueryBudget)` propagates the budget to the whole subtree; `protected final void countTuple()` must be called by each concrete operator's `getNextTuple()` on every non-null tuple — this is what enforces total-work semantics. Abstract `describe()` returns a one-line description for `PlanPrinter`. |
| `ScanOperator.java` | `com.github.jinba1.cuckoodb.operator` | Leaf operator; reads CSV via commons-csv RFC 4180, skips header row, emits typed tuples using column types from DBCatalog. |
| `SelectOperator.java` | `com.github.jinba1.cuckoodb.operator` | Unary filter; applies a WHERE Expression via ExpressionEvaluator, passing through matching tuples. |
| `ProjectOperator.java` | `com.github.jinba1.cuckoodb.operator` | Unary; projects a subset of columns, rewriting the schema. |
| `JoinOperator.java` | `com.github.jinba1.cuckoodb.operator` | Binary nested-loop join; has outerChild and child (inner); optional join condition; propagates merged schema. Used for cross products and pure non-equi joins. |
| `HashJoinOperator.java` | `com.github.jinba1.cuckoodb.operator` | Extends `JoinOperator`. Build phase drains the inner child into a `HashMap` keyed by equality-conjunct column values; probe phase streams the outer child and probes the map. Re-evaluates the full original condition on every candidate to handle residual non-equi conjuncts. Output order and EXPLAIN label (`HashJoin[...]`) differ from `JoinOperator` (`Join[...]`); auto-selected by `QueryPlanner` for equi-joins when `Constants.useHashJoin` is true. |
| `SortOperator.java` | `com.github.jinba1.cuckoodb.operator` | Unary; materializes child tuples then sorts via TupleComparator by ORDER BY columns. |
| `AggregateOperator.java` | `com.github.jinba1.cuckoodb.operator` | Blocking operator for GROUP BY + SUM/COUNT/AVG/MIN/MAX. Groups tuples by key columns, creates one Accumulator per aggregate call per group, then emits one output tuple per group. |
| `Accumulator.java` | `com.github.jinba1.cuckoodb.operator` | Package-private interface for per-group, per-call aggregate state. `add(Value)` folds one row's argument; `result()` returns the final Value. Static factory `create(AggregateCall)` dispatches to IntSumAccumulator (SUM/AVG), CountAccumulator, or MinMaxAccumulator. |
| `LimitOperator.java` | `com.github.jinba1.cuckoodb.operator` | Unary; emits at most `limit` tuples from its child then signals EOF. Placed at the top of the plan by the planner. |
| `DuplicateEliminationOperator.java` | `com.github.jinba1.cuckoodb.operator` | DISTINCT; materializes child tuples into a LinkedHashSet to drop duplicates while preserving first-seen order. |

## CONVENTIONS

- **Iterator model:** All operators extend `Operator` and implement `getNextTuple()` / `reset()`. Unary operators use `protected Operator child`; `JoinOperator` adds `outerChild` for its outer input.
- **Schema tracking:** Operators register schema transformations with `DBCatalog`; intermediate schemas get the `Constants.INTERMEDIATE_SCHEMA_PREFIX` (`"temp_"`) prefix. `INTERMEDIATE_SCHEMA_PREFIX` is used in production code in `QueryPlanOptimizer`, `ConditionSplitter`, and `JoinOperator`.
- **Lazy schema registration:** The `schemaRegistered` flag and `ensureSchemaRegistered()` pattern is used across operators to defer schema registration until first use.
- **No dependency injection:** `DBCatalog` is a mutable singleton. Tests call `resetDBCatalog()` in their `@BeforeEach` setup (JUnit 5).
- **Typed values:** `Tuple` stores `List<Value>` (sealed: `IntValue`, `StringValue`). Column types inferred at catalog init (INT iff every field parses as int). Column names stored lowercase.
- **Output format:** Query output begins with a header row (column names, plain commas), followed by data rows (plain comma-separated, LF line endings, RFC 4180). Output is round-trippable as input.

## COMMANDS

```bash
# Run the full test suite (339 tests)
./mvnw test

# Build the fat JAR (engine module; assembly is bound to the package phase)
./mvnw -pl engine -DskipTests clean package

# Or build the whole reactor (engine fat JAR + server skeleton), running tests
./mvnw clean package

# The fat JAR is always produced at:
# engine/target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar

# Run a query (example: query1.sql)
java -cp engine/target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.cuckoodb.CuckooDB \
  engine/samples/db \
  engine/samples/input/query1.sql \
  output.csv

# Run a query with budget flags (both optional, independent)
java -cp engine/target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.cuckoodb.CuckooDB \
  engine/samples/db \
  engine/samples/input/query1.sql \
  output.csv \
  --max-tuples=50000 \
  --timeout-ms=10000

# Run all 20 sample queries via the automated runner.
# SampleQueryRunner.main resolves samples/ against the CWD, so it MUST run with
# CWD = engine/. exec:java keeps CWD at the reactor root (would not find samples/);
# exec:exec forks with workingDirectory = engine/ basedir, so use this form
# (or: cd engine && java -cp target/cuckoodb-engine-1.0.0-jar-with-dependencies.jar com.github.jinba1.cuckoodb.SampleQueryRunner).
./mvnw -pl engine -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
  "-Dexec.args=-cp %classpath com.github.jinba1.cuckoodb.SampleQueryRunner"

# Run the JMH benchmark suite (exec:java breaks JMH forking; use this exact form)
./mvnw -pl engine -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
  "-Dexec.args=-cp %classpath org.openjdk.jmh.Main .*Benchmark"
```

## CI

The project ships `.github/workflows/ci.yml` which triggers on push and pull_request to `main`. It runs on `ubuntu-latest` with JDK 17 (Temurin distribution) and caches `~/.m2`.

Steps:
1. **Build** — `./mvnw clean compile`
2. **Test** — `./mvnw test` (339 tests; JMH benchmark classes compile here but are not executed)
3. **Coverage upload** — uploads JaCoCo coverage reports to Codecov via `codecov/codecov-action@v5` (token from `secrets.CODECOV_TOKEN`). JaCoCo plugin version 0.8.12 generates the coverage report.

The README displays CI, Coverage (Codecov), and Dependencies badges at the top.

## NOTES

- The test suite currently passes 339 tests with zero failures or errors: `Tests run: 339, Failures: 0, Errors: 0, Skipped: 0`.
- The benchmarking/tuple-counter infrastructure was introduced in commit `ef92ca1` ("feat: add query optimization benchmark suite with tuple counters"): `Operator` gained `protected long tupleCounter` with `getTupleCount()` / `resetTupleCount()`, and `QueryOptimizationBenchmarkTest` was added as one of the test files.
- Budget enforcement reuses the tuple-counter slot: `countTuple()` (called per emitted tuple) increments `tupleCounter` and delegates to `QueryBudget.charge()` when a budget is attached. This means every operator in the tree counts — total-work semantics.
- `SampleQueryRunner.java` provides an automated 20-query diff runner: it runs all queries in `samples/input/` against `samples/db/` and diffs each result against `samples/expected_output/`, reporting pass/fail. There is no need to diff manually.
- A `.gitignore` exists at the repository root. It ignores `target/`, `*.iml`, `.DS_Store`, `*.class`, `.omo/`, and the engine-module test-resource output directories (`engine/src/test/resources/test_integration_output/`, `engine/src/test/resources/test_sample_output/`, `engine/src/test/resources/test_integration_queries/`).
- Query output includes a header row (column names, plain commas) followed by data rows (plain comma-separated, LF). Output is RFC 4180 and is round-trippable as input to this engine.
- EXPLAIN queries write the two-section plan text to the output file and do not execute the query. The plan root returned by `QueryPlanner.planQuery` for EXPLAIN is a no-op; `CuckooDB.run` short-circuits before operator iteration when `explainText` is non-null.
- Hash join is the default for equi-joins (`Constants.useHashJoin = true`). Set it to `false` in tests (via `@BeforeEach`/`@AfterEach`) to exercise the nested-loop path. `HashJoinOperator.hasEquiConjunct(Expression)` is the planner's check for at least one cross-side column=column equality in the condition.
- The JMH 1.37 benchmark suite lives in `engine/src/test/java/com/github/jinba1/cuckoodb/bench/`. It is compiled as part of `test-compile` in CI but never executed there. `CachedOperator` (in `engine/src/test/java/.../operator/`) is a test utility operator that replays a fixed in-memory tuple list — used by `JoinAlgorithmBenchmark` to isolate join algorithm cost from CSV I/O. The `bench/` package classes are JMH benchmarks and do not contain JUnit tests.
