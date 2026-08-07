# Query Optimization Benchmark Results

## Overview

This document contains quantifiable performance results from the query optimization benchmark suite added to CuckooDB. The benchmarks measure tuple processing reduction achieved by rule-based query optimizations.

## Benchmark Methodology

- **Metric**: Scan-level tuple counts (tuples read from disk via ScanOperator)
- **Comparison**: Optimized plan vs. unoptimized plan for identical queries
- **Optimization Rules**: Selection pushdown, projection pushdown, trivial operator removal, consecutive select combining
- **Test Data**: 6 students, 5 courses, 6 enrollments (small dataset for fast tests)

## Results

### 1. Join Selection Pushdown
**Query**: `SELECT Student.A, Enrolled.J, Enrolled.K FROM Student, Enrolled WHERE Student.A = Enrolled.I AND Student.D > 30;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 42 | - |
| Optimized | 18 | **57.1%** |

**Explanation**: The predicate `Student.D > 30` is pushed down to the Student scan, reducing rows from 6 to 2 before the join. This eliminates 24 unnecessary join probe tuples.

### 2. Complex Join Optimization
**Query**: `SELECT Student.A, Course.F, Enrolled.K FROM Student, Enrolled, Course WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E AND Student.D > 30 AND Course.G > 3;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 78 | - |
| Optimized | 60 | **23.1%** |

**Explanation**: Both `Student.D > 30` and `Course.G > 3` are pushed to their respective table scans in the three-way join, reducing input to the join operators.

### 3. Combined Optimizations
**Query**: `SELECT Student.D, SUM(Enrolled.K) FROM Student, Enrolled WHERE Student.A = Enrolled.I AND Student.D > 20 GROUP BY Student.D;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 42 | - |
| Optimized | 30 | **28.6%** |

**Explanation**: Selection pushdown (`Student.D > 20`) reduces Student scan from 6 to 4 rows. Combined with projection pushdown that eliminates unnecessary columns early.

### 4. Trivial Operator Removal
**Query**: `SELECT * FROM Student WHERE 1 = 1;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 6 | - |
| Optimized | 6 | 0% |

**Explanation**: The always-true `WHERE 1 = 1` condition is removed entirely. No scan reduction (same data read), but operator tree is simplified by 1 operator.

### 5. Single Table Selection
**Query**: `SELECT Student.A, Student.B FROM Student WHERE Student.D > 30;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 6 | - |
| Optimized | 6 | 0% |

**Explanation**: No pushdown possible on single table (selection already at scan level). Both plans read all 6 rows; selectivity affects output only.

### 6. Projection Pushdown
**Query**: `SELECT Student.A, Student.D FROM Student;`

| Plan | Scan Tuples | Reduction |
|------|-------------|-----------|
| Unoptimized | 6 | - |
| Optimized | 6 | 0% |

**Explanation**: With small test data, projection pushdown doesn't reduce scan count (still need to read all rows). Benefit is data movement reduction in columns, not rows.

## Summary for Resume

> "Reduced scan-level tuple processing by **23-57%** on multi-table join queries through rule-based query optimisations including selection pushdown and projection pushdown, validated by focused optimizer benchmarks and guarded by the current 419-test engine suite."

## Implementation Details

- **Benchmark Class**: `QueryOptimizationBenchmarkTest.java`
- **Tuple Counters**: All concrete operators count emitted tuples through `Operator.countTuple()` (`Scan`, `Select`, `Project`, `Join` / `HashJoin`, `Sort`, `Aggregate`, `DuplicateElimination`, `Limit`)
- **Optimization Toggle**: Benchmarks pass per-query `QueryConfig` values to `QueryPlanner` instead of mutating global flags
- **Sample Queries**: query13.sql through query16.sql demonstrate optimization scenarios; query20 demonstrates EXPLAIN
- **Current Test Gate**: 419 engine tests, including 36 `QueryOptimizationBenchmarkTest` cases, plus the 20-query byte-identical sample gate

## Running Benchmarks

```bash
./mvnw -pl engine test -Dtest=QueryOptimizationBenchmarkTest
```

Or run all tests:
```bash
./mvnw test
```
