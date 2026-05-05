# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-05
**Commit:** 868f1b2
**Branch:** main

## OVERVIEW
BlazeDB — in-memory relational query engine (Volcano/iterator model). Java 17 / Maven / JUnit 5. Parses SQL via JSqlParser, builds operator tree, executes tuple-at-a-time.

## STRUCTURE
```
.
├── src/main/java/com/github/jinba1/blazedb/     # Core domain (13 files)
│   └── operator/                            # Volcano operators (8 files)
├── src/test/java/com/github/jinba1/blazedb/      # JUnit 5 tests (15 files)
├── samples/                                 # SQL queries + expected output + DB data
│   ├── db/schema.txt                        # Table schemas
│   ├── db/data/                             # CSV data files
│   ├── input/query[1-12].sql                # Sample queries
│   └── expected_output/query[1-12].csv      # Expected results
└── pom.xml                                  # Maven build (Java 8, JSqlParser 4.7, JUnit 4.13.2)
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Entry point / CLI | `BlazeDB.java` | `main(args)` — takes db_dir, input_file, output_file |
| SQL → operator tree | `QueryPlanner.java` | Parses SQL, builds operator pipeline |
| Query optimization | `QueryPlanOptimizer.java` | Selection pushdown, trivial op removal; toggled via `Constants.useQueryOptimization` |
| Schema / catalog | `DBCatalog.java` | Mutable singleton; `initDBCatalog()` / `resetDBCatalog()` |
| Expression eval | `ExpressionEvaluator.java` | Evaluates WHERE/HAVING conditions per tuple |
| Condition splitting | `ConditionSplitter.java` | Splits join predicates from selection predicates |
| Expression preprocessing | `ExpressionPreprocessor.java` | Resolves column references to indices before evaluation |
| Column extraction | `ColumnExtractor.java` | Visitor that pulls column names from expressions |
| Column identity | `ColumnIdentity.java` | Represents fully-qualified column (table + name) |
| Tuple representation | `Tuple.java` | `List<Integer>` — all values are integers |
| Sorting comparator | `TupleComparator.java` | Multi-column sort comparator |
| Schema transform enum | `SchemaTransformationType.java` | Enum for schema operations |
| Global config | `Constants.java` | `useQueryOptimization`, file prefixes, `INTERMEDIATE_SCHEMA_PREFIX` |

## CONVENTIONS
- **Iterator model**: All operators extend `Operator`, implement `getNextTuple()` / `reset()`. Unary ops use `protected Operator child`. JoinOperator adds `outerChild`.
- **Schema tracking**: Every operator registers schema transformation with `DBCatalog.registerSchema()`. Intermediate schemas get `temp_` + UUID prefix.
- **Lazy registration**: `schemaRegistered` flag + `ensureSchemaRegistered()` pattern throughout operators.
- **No DI**: `DBCatalog` is mutable singleton. Tests must call `resetDBCatalog()` in `@Before`.
- **All values are integers**: `Tuple` stores `List<Integer>`. Column names stored lowercase.
- **Error handling**: `RuntimeException` for resolution failures, `UnsupportedOperationException` for unsupported SQL, `Error` in some QueryPlanner cases (inconsistent).
- **Output format**: comma-space separated (`", "`).

## ANTI-PATTERNS (THIS PROJECT)
- **72 commented-out `System.out.println` debug lines** across 9 files — use git history, not comments.
- **Dead code** in `BlazeDB.java`: commented-out `reportOperatorCounts()` method and `parsingExample()` call.
- **`"temp_"` hardcoded in `ConditionSplitter.java` line 189** instead of using `Constants.INTERMEDIATE_SCHEMA_PREFIX`.
- **`Error` thrown in `QueryPlanner`** where `RuntimeException` should be used.
- **`dBLocations` / `dBSchemata`** field names in `DBCatalog` — inconsistent camelCase (should be `dbLocations`).
- **JDK mismatch**: pom.xml targets Java 8, IntelliJ configures JDK 21.

## COMMANDS
```bash
# Compile
mvn compile

# Run tests
mvn test

# Build fat JAR (mvn package alone won't produce it — no execution binding)
mvn clean compile assembly:single

# Run
java -cp target/blazedb-1.0.0-jar-with-dependencies.jar com.github.jinba1.blazedb.BlazeDB samples/db samples/input/query1.sql output.csv
```

## NOTES
- No `.gitignore` at root — `target/`, `.idea/` may be tracked.
- `maven-assembly-plugin` has no `<execution>` binding to `package` phase — must call `assembly:single` explicitly.
- `samples/expected_output/` has expected CSVs for 12 queries but no automated diff runner.
- No CI/CD pipeline. Tests run locally only.
