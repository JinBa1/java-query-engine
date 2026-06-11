# PROJECT KNOWLEDGE BASE

**Generated:** 2026-06-11
**Commit:** 6fba02a
**Branch:** feat/typed-columns

## OVERVIEW

BlazeDB (artifactId: `java-query-engine`) is an in-memory relational query engine built on the Volcano/iterator model. It parses SQL via JSqlParser, builds an operator tree, and executes tuple-at-a-time over CSV data. The project targets Java 17 and uses JUnit Jupiter 5.10.2, JSqlParser 4.7, and JaCoCo 0.8.12.

## STRUCTURE

```
java-query-engine/
├── pom.xml                          # Java 17, JSqlParser 4.7, commons-csv 1.14.1, JUnit 5.10.2
├── .gitignore                       # ignores target/, *.class, .iml, .DS_Store, test output dirs
├── .github/
│   └── workflows/
│       └── ci.yml                   # push/PR to main: build + test + Codecov upload
├── samples/
│   ├── db/
│   │   └── data/                    # CSV data files (header row + data rows)
│   ├── input/
│   │   └── query[1-12].sql          # 12 sample queries
│   └── expected_output/
│       └── query[1-12].csv          # expected results
└── src/
    ├── main/java/com/github/jinba1/blazedb/   # 19 core files
    │   ├── BlazeDB.java
    │   ├── QueryPlanner.java
    │   ├── QueryPlanOptimizer.java
    │   ├── DBCatalog.java
    │   ├── ExpressionEvaluator.java
    │   ├── ExpressionPreprocessor.java
    │   ├── ConditionSplitter.java
    │   ├── ColumnExtractor.java
    │   ├── ColumnIdentity.java
    │   ├── Tuple.java
    │   ├── TupleComparator.java
    │   ├── SchemaTransformationType.java
    │   ├── Constants.java
    │   ├── SampleQueryRunner.java
    │   ├── Value.java
    │   ├── IntValue.java
    │   ├── StringValue.java
    │   ├── ColumnType.java
    │   ├── QueryExecutionException.java
    │   └── operator/                # 8 operator files (1 abstract base + 7 concrete)
    │       ├── Operator.java
    │       ├── ScanOperator.java
    │       ├── SelectOperator.java
    │       ├── ProjectOperator.java
    │       ├── JoinOperator.java
    │       ├── SortOperator.java
    │       ├── SumOperator.java
    │       └── DuplicateEliminationOperator.java
    └── test/java/com/github/jinba1/blazedb/   # 20 test files
        ├── BlazeDBTest.java
        ├── ColumnExtractorTest.java
        ├── ConditionSplitterTest.java
        ├── DBCatalogTest.java
        ├── DuplicateEliminationOperatorTest.java
        ├── ExpressionEvaluatorTest.java
        ├── ExpressionPreprocessorTest.java
        ├── JoinOperatorTest.java
        ├── ProjectOperatorTest.java
        ├── QueryOptimizationBenchmarkTest.java
        ├── QueryPlanOptimizerTest.java
        ├── ScanOperatorTest.java
        ├── SelectOperatorTest.java
        ├── SortOperatorTest.java
        ├── SumOperatorTest.java
        ├── TupleComparatorTest.java
        ├── TupleTest.java
        ├── TestTuples.java
        ├── StringEndToEndTest.java
        └── ValueTest.java
```

## WHERE TO LOOK

| File | Package | Notes |
|------|---------|-------|
| `BlazeDB.java` | `com.github.jinba1.blazedb` | Entry point. `public static void main(String[] args)` takes db-dir, input SQL file, output CSV path; parses via QueryPlanner then executes, writing a header row followed by tuple rows via commons-csv RFC 4180 (LF line endings) to the output file. |
| `QueryPlanner.java` | `com.github.jinba1.blazedb` | Translates a SQL file into an executable operator tree (parseStatement); builds the scan/join/select/project/group-by-SUM/sort/distinct pipeline. |
| `QueryPlanOptimizer.java` | `com.github.jinba1.blazedb` | Optimization passes: selection pushdown, trivial project/select removal, consecutive-select merging, projection pushdown. Toggled by Constants.useQueryOptimization. |
| `DBCatalog.java` | `com.github.jinba1.blazedb` | Mutable singleton: CSV-header table discovery + INT/STRING type inference at init. Table-to-path map (dbLocations), table schemas (dbSchemata), column types (dbColumnTypes). initDBCatalog(dir) / resetDBCatalog(); getColumnTypes(), getOrderedColumnNames(). No schema.txt. |
| `ExpressionEvaluator.java` | `com.github.jinba1.blazedb` | JSqlParser visitor evaluating WHERE/HAVING expressions against a Tuple (boolean and Value evaluation). Type-checked comparisons; string literals via StringValue. |
| `ExpressionPreprocessor.java` | `com.github.jinba1.blazedb` | JSqlParser visitor that separates two-table join predicates from single-table selection predicates during planning. |
| `ConditionSplitter.java` | `com.github.jinba1.blazedb` | Splits a join condition into outer-only, inner-only, and true join predicate parts (used by optimizer pushdown). Uses Constants.INTERMEDIATE_SCHEMA_PREFIX. |
| `ColumnExtractor.java` | `com.github.jinba1.blazedb` | Visitor that collects Column references from an expression. |
| `ColumnIdentity.java` | `com.github.jinba1.blazedb` | Value object wrapping a Column with equality/hashCode by table+column name; includes a column-deduplication utility. |
| `Tuple.java` | `com.github.jinba1.blazedb` | Row of typed values: wraps List<Value>; getAttribute(i) returns Value, toString() (comma-space separated), equals/hashCode. |
| `TupleComparator.java` | `com.github.jinba1.blazedb` | Comparator<Tuple> for multi-column lexicographic sorting by column indices. |
| `SchemaTransformationType.java` | `com.github.jinba1.blazedb` | Enum marking the kind of schema transformation an operator performs. |
| `Constants.java` | `com.github.jinba1.blazedb` | App constants: useQueryOptimization (boolean, default true), INTERMEDIATE_SCHEMA_PREFIX = "temp_", DATA_DIRECTORY_NAME = "data", SUM_FUNCTION_NAME = "SUM". |
| `SampleQueryRunner.java` | `com.github.jinba1.blazedb` | Standalone main that runs all 12 sample queries against samples/db and diffs each output against samples/expected_output/, reporting pass/fail. |
| `Value.java` | `com.github.jinba1.blazedb` | Sealed interface for typed tuple values; permits IntValue, StringValue; extends Comparable<Value>; declares typeName(). |
| `IntValue.java` | `com.github.jinba1.blazedb` | Record implementing Value; wraps int v(); compareTo orders numerically. |
| `StringValue.java` | `com.github.jinba1.blazedb` | Record implementing Value; wraps String v(); compareTo orders lexicographically. |
| `ColumnType.java` | `com.github.jinba1.blazedb` | Enum: INT, STRING. Used by DBCatalog and ScanOperator. |
| `QueryExecutionException.java` | `com.github.jinba1.blazedb` | Unchecked exception for data/type errors at runtime; messages state operation, column/literal, and both types for agent-legible diagnostics. |
| `Operator.java` | `com.github.jinba1.blazedb.operator` | Abstract base for all Volcano operators. Defines getNextTuple(), reset(), schema methods (propagateSchemaId, registerSchema, ensureSchemaRegistered, updateSchema). Holds `protected Operator child`, a schemaRegistered flag, and `protected long tupleCounter` for benchmarking (with getTupleCount()/resetTupleCount()). |
| `ScanOperator.java` | `com.github.jinba1.blazedb.operator` | Leaf operator; reads CSV via commons-csv RFC 4180, skips header row, emits typed tuples using column types from DBCatalog. |
| `SelectOperator.java` | `com.github.jinba1.blazedb.operator` | Unary filter; applies a WHERE Expression via ExpressionEvaluator, passing through matching tuples. |
| `ProjectOperator.java` | `com.github.jinba1.blazedb.operator` | Unary; projects a subset of columns, rewriting the schema. |
| `JoinOperator.java` | `com.github.jinba1.blazedb.operator` | Binary nested-loop join; has outerChild and child (inner); optional join condition; propagates merged schema. |
| `SortOperator.java` | `com.github.jinba1.blazedb.operator` | Unary; materializes child tuples then sorts via TupleComparator by ORDER BY columns. |
| `SumOperator.java` | `com.github.jinba1.blazedb.operator` | GROUP BY + SUM aggregation; groups by key columns, accumulates SUM, emits one tuple per group. |
| `DuplicateEliminationOperator.java` | `com.github.jinba1.blazedb.operator` | DISTINCT; materializes child tuples into a LinkedHashSet to drop duplicates while preserving first-seen order. |

## CONVENTIONS

- **Iterator model:** All operators extend `Operator` and implement `getNextTuple()` / `reset()`. Unary operators use `protected Operator child`; `JoinOperator` adds `outerChild` for its outer input.
- **Schema tracking:** Operators register schema transformations with `DBCatalog`; intermediate schemas get the `Constants.INTERMEDIATE_SCHEMA_PREFIX` (`"temp_"`) prefix. `INTERMEDIATE_SCHEMA_PREFIX` is used in production code in `QueryPlanOptimizer`, `ConditionSplitter`, and `JoinOperator`.
- **Lazy schema registration:** The `schemaRegistered` flag and `ensureSchemaRegistered()` pattern is used across operators to defer schema registration until first use.
- **No dependency injection:** `DBCatalog` is a mutable singleton. Tests call `resetDBCatalog()` in their `@BeforeEach` setup (JUnit 5).
- **Typed values:** `Tuple` stores `List<Value>` (sealed: `IntValue`, `StringValue`). Column types inferred at catalog init (INT iff every field parses as int). Column names stored lowercase.
- **Output format:** Query output begins with a header row (column names, plain commas), followed by data rows (plain comma-separated, LF line endings, RFC 4180). Output is round-trippable as input.

## COMMANDS

```bash
# Run the full test suite (262 tests)
./mvnw test

# Build the fat JAR explicitly
./mvnw clean compile assembly:single

# Build the fat JAR via the package phase (assembly plugin is bound to package phase — also works)
./mvnw clean package

# The fat JAR is always produced at:
# target/java-query-engine-1.0.0-jar-with-dependencies.jar

# Run a query (example: query1.sql)
java -cp target/java-query-engine-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.blazedb.BlazeDB \
  samples/db \
  samples/input/query1.sql \
  output.csv
```

## CI

The project ships `.github/workflows/ci.yml` which triggers on push and pull_request to `main`. It runs on `ubuntu-latest` with JDK 17 (Temurin distribution) and caches `~/.m2`.

Steps:
1. **Build** — `./mvnw clean compile`
2. **Test** — `./mvnw test`
3. **Coverage upload** — uploads JaCoCo coverage reports to Codecov via `codecov/codecov-action@v5` (token from `secrets.CODECOV_TOKEN`). JaCoCo plugin version 0.8.12 generates the coverage report.

The README displays CI, Coverage (Codecov), and Dependencies badges at the top.

## NOTES

- The test suite currently passes 262 tests with zero failures or errors: `Tests run: 262, Failures: 0, Errors: 0, Skipped: 0`.
- The benchmarking/tuple-counter infrastructure was introduced in commit `ef92ca1` ("feat: add query optimization benchmark suite with tuple counters"): `Operator` gained `protected long tupleCounter` with `getTupleCount()` / `resetTupleCount()`, and `QueryOptimizationBenchmarkTest` was added as one of the 20 test files.
- `SampleQueryRunner.java` provides an automated 12-query diff runner: it runs all queries in `samples/input/` against `samples/db/` and diffs each result against `samples/expected_output/`, reporting pass/fail. There is no need to diff manually.
- A `.gitignore` exists at the repository root. It ignores `target/`, `*.iml`, `.DS_Store`, `*.class`, `.omo/`, and the test-resource output directories (`src/test/resources/test_integration_output/`, `src/test/resources/test_sample_output/`, `src/test/resources/test_integration_queries/`).
- Query output includes a header row (column names, plain commas) followed by data rows (plain comma-separated, LF). Output is RFC 4180 and is round-trippable as input to this engine.
