# BlazeDB — Java Relational Query Engine

![CI](https://github.com/JinBa1/ADS-CW/actions/workflows/ci.yml/badge.svg)

BlazeDB is an in-memory relational query engine built on the Volcano/iterator model. It parses SQL statements via JSqlParser, constructs an operator tree, and executes queries tuple-by-tuple against CSV data files.

## Overview

The engine reads a SQL query and a directory of CSV tables, plans and optimises the query, then streams results through a pipeline of operators. Each operator exposes `getNextTuple()` and `reset()`, following the classic Volcano execution model. All values are integers.

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
| `ExpressionEvaluator` | Evaluates WHERE/HAVING conditions per tuple |
| `ExpressionPreprocessor` | Resolves column references to indices |
| `ConditionSplitter` | Separates join predicates from selection predicates |

**Operator hierarchy** (all extend `Operator`):

`ScanOperator` → `SelectOperator` → `ProjectOperator` → `JoinOperator` → `SortOperator` → `SumOperator` → `DuplicateEliminationOperator`

## Supported SQL Features

| Feature | Example |
|---------|---------|
| `SELECT *` | `SELECT * FROM Student;` |
| Projection | `SELECT Student.D, Student.B FROM Student;` |
| `WHERE` filter | `SELECT * FROM Student WHERE Student.A = 1;` |
| Inner join | `SELECT * FROM Student, Enrolled WHERE Student.A = Enrolled.A;` |
| `ORDER BY` | `SELECT * FROM Student ORDER BY Student.B;` |
| `GROUP BY` + `SUM` | `SELECT SUM(1) FROM Student GROUP BY Student.B;` |
| `DISTINCT` | `SELECT DISTINCT Student.A FROM Student;` |
| Nested expressions | Arithmetic and comparison expressions in WHERE/HAVING |

## Quick Start

**Prerequisites:** Java 17, Maven (or use the included Maven Wrapper).

```bash
# Clone
git clone https://github.com/JinBa1/ADS-CW.git
cd ADS-CW

# Build fat JAR
./mvnw clean compile assembly:single
```

**Run a query:**

```bash
java -cp target/blazedb-1.0.0-jar-with-dependencies.jar \
  com.github.jinba1.blazedb.BlazeDB \
  samples/db samples/input/query1.sql output.csv
```

## Running Examples

The `samples/` directory ships with 12 queries and a small dataset (Student, Course, Enrolled tables). Expected output lives in `samples/expected_output/`.

```bash
# Run all sample queries and diff against expected output
for i in $(seq 1 12); do
  java -cp target/blazedb-1.0.0-jar-with-dependencies.jar \
    com.github.jinba1.blazedb.BlazeDB \
    samples/db "samples/input/query${i}.sql" "/tmp/out${i}.csv"
  diff "samples/expected_output/query${i}.csv" "/tmp/out${i}.csv" && echo "query${i}: OK"
done
```

## Testing

```bash
./mvnw test
```

The test suite covers individual operators, the query planner, the optimiser, expression evaluation, and end-to-end integration scenarios.

## Project Structure

```
├── src/main/java/com/github/jinba1/blazedb/   # Core engine (21 files)
│   └── operator/                                # Volcano operators (8 files)
├── src/test/java/com/github/jinba1/blazedb/    # JUnit 5 tests
├── samples/
│   ├── db/schema.txt                            # Table schemas
│   ├── db/data/                                 # CSV data files
│   ├── input/query[1-12].sql                    # Sample queries
│   └── expected_output/query[1-12].csv          # Expected results
├── pom.xml                                      # Maven config (Java 17, JSqlParser 4.7)
├── mvnw / mvnw.cmd                              # Maven Wrapper
└── LICENSE
```

## Background

Originally developed as coursework for the Advanced Database Systems course at the University of Edinburgh, subsequently extended with additional query optimisation and expanded test coverage.

## License

This project is released under the MIT License. See [LICENSE](LICENSE) for details.
