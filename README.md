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

`ScanOperator` → `SelectOperator` → `ProjectOperator` → `JoinOperator` → `SortOperator` → `SumOperator` → `DuplicateEliminationOperator`

## Feature Matrix

| Feature | Status |
|---------|--------|
| `SELECT *` / projection | ✅ Supported |
| `WHERE` predicates | ✅ Supported |
| Inner joins (nested-loop) | ✅ Supported |
| `ORDER BY` | ✅ Supported |
| `GROUP BY` + `SUM` | ✅ Supported |
| `DISTINCT` | ✅ Supported |
| Nested arithmetic/comparison expressions | ✅ Supported |
| Query optimisation (selection pushdown) | ✅ Supported |
| Typed columns (int, string) | ✅ Supported |
| CSV header support | ✅ Supported |
| Indexes | ❌ Not supported |
| Transactions | ❌ Not supported |
| INSERT / UPDATE / DELETE | ❌ Not supported |
| Concurrency | ❌ Not supported |
| Persistence | ❌ Not supported |
| Full SQL dialect | ❌ Not supported |

## Scope

This engine supports **SQL-over-CSV query execution**: read-only queries against tables stored as CSV files. It does not support transactions, indexes, data modification (INSERT/UPDATE/DELETE), concurrency, persistence, or a full SQL dialect. Values are typed int or string, inferred per column from the data. Tables are discovered from CSV files with header rows; no separate schema file.

The focus is on demonstrating query planning, optimisation, and the Volcano iterator execution model.

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
  samples/db samples/input/query1.sql output.csv
```

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

**Output** (`output.csv`):

```
a,b,c,d
1,200,50,33
2,200,200,44
```

## Running Examples

The `samples/` directory ships with 19 queries and a small dataset (Student, Course, Enrolled, Staff tables). Expected output lives in `samples/expected_output/`.

```bash
# Run all sample queries and diff against expected output
for i in $(seq 1 19); do
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

The test suite covers individual operators, the query planner, the optimiser, expression evaluation, and end-to-end integration scenarios (262 tests).

## Project Structure

```
├── src/main/java/com/github/jinba1/blazedb/   # Core engine (27 files)
│   └── operator/                                # Volcano operators (8 files)
├── src/test/java/com/github/jinba1/blazedb/    # JUnit 5 tests
├── samples/
│   ├── db/data/                                 # CSV data files (header row + data rows)
│   ├── input/query[1-19].sql                    # Sample queries
│   └── expected_output/query[1-19].csv          # Expected results
├── pom.xml                                      # Maven config (Java 17, JSqlParser 4.7, commons-csv 1.14.1)
├── mvnw / mvnw.cmd                              # Maven Wrapper
└── LICENSE
```

## Background

Originally built as a university project for the Advanced Database Systems course at the University of Edinburgh, subsequently extended with additional query optimisation and expanded test coverage.

## License

This project is released under the MIT License. See [LICENSE](LICENSE) for details.
