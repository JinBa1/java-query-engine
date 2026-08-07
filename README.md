# cuckooDB

![CI](https://github.com/JinBa1/java-query-engine/actions/workflows/ci.yml/badge.svg)
![Coverage](https://codecov.io/gh/JinBa1/java-query-engine/branch/main/graph/badge.svg)
![Dependencies](https://img.shields.io/badge/dependencies-up%20to%20date-brightgreen)

**A self-hosted gateway that gives AI agents safe, budgeted SQL query access to your CSV files — no database required.**

Everyone has CSVs — exports, dumps, logs — and AI agents increasingly need to query them. Embedding a database in every agent environment hands over raw file access; what you actually want is a *guarded window* onto the data: read-only query execution, resource budgets, and audit hooks around a narrow data surface. cuckooDB is that gateway, built on a from-scratch query engine and exposed over both a **REST API** and the **Model Context Protocol (MCP)**, so an agent can discover tables, preview data, check a query's cost, and run SQL — without writing SQL blind or bypassing the guardrails.

## Features

| Capability | |
|---|:--:|
| Read-only SQL query execution over CSV — `SELECT` / `WHERE` / `JOIN` / `GROUP BY` / `ORDER BY` / `LIMIT` / `DISTINCT` | ✅ |
| Aggregates — `COUNT` / `SUM` / `AVG` / `MIN` / `MAX` | ✅ |
| Hash + nested-loop joins (planner auto-selects) | ✅ |
| Typed columns (int / string), CSV headers | ✅ |
| `EXPLAIN` plan inspection | ✅ |
| Tuple + time budgets, fail-closed | ✅ |
| **REST API** + OpenAPI / Swagger | ✅ |
| **MCP server** — five agent tools, Streamable-HTTP | ✅ |
| Opt-in CSV upload endpoint, disabled by default | ✅ |
| Runs as a container (published to GHCR) | ✅ |
| SQL writes / transactions / indexes / database persistence | ❌ query path is read-only by design |

## Quick start

Run the gateway next to your data — no Java toolchain needed. Put your CSVs in a folder and mount it:

```bash
docker run --rm -p 8080:8080 \
  -v /path/to/your/csvs:/cuckoodb/data \
  ghcr.io/jinba1/cuckoodb:latest
```

Query over REST:

```bash
curl -s localhost:8080/tables
# ["People"]

curl -s localhost:8080/queries -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM People LIMIT 5"}'
# {"columns":[{"name":"id","type":"INT"},...],"rows":[[1,"alice"],...],"rowCount":5,"truncated":true,"hint":"..."}
```

…or connect an AI agent over MCP (below). To use the engine directly from the command line instead, see the **[engine README](engine/README.md)**.

## For agents: MCP

The server exposes a Model Context Protocol endpoint at `http://localhost:8080/mcp` (Streamable-HTTP). Point an MCP client (e.g. Claude Desktop) at it and the agent gets five tools:

| Tool | What it does |
|---|---|
| `list_tables` | list the available tables |
| `describe_table` | a table's column names and types |
| `sample_rows` | preview rows without writing SQL |
| `explain_query` | preview a query's plan and cost before running it |
| `query` | run a read-only `SELECT`, budget-bounded |

Every query-running tool routes through the same guarded execution path as the REST query API, so agent traffic inherits the read-only query guarantee, the tuple/time budget, and concurrency limits (with audit hooks in place) — there is no way to bypass them.

## REST API

| Endpoint | |
|---|---|
| `POST /queries` | plan + execute one read-only query → JSON columns/rows, or an `EXPLAIN` plan |
| `GET /tables` | list table names |
| `GET /tables/{name}` | a table's typed schema |
| `POST /tables/{name}` | opt-in `text/csv` upload as a process-lifetime table; disabled by default |
| `/swagger-ui.html` | interactive OpenAPI docs |

Queries are **budget-bounded and fail-closed**: the server always attaches a budget, so an unbounded query is unreachable. A result that would exceed the tuple budget returns `429` (retry with a tighter `LIMIT`); one that exceeds the time budget returns `504`.

Uploads are off unless a deployment sets `cuckoodb.upload.enabled=true`. When enabled, uploads enforce table-name validation, a streamed byte cap, a process-wide table cap, malformed-CSV rejection, and `409` on name clashes.

### EXPLAIN

Any query can be planned without executing it — prefix `EXPLAIN` over REST, or call the `explain_query` tool. The plan is shown as written and after optimisation:

```
=== Plan (as written) ===
Project[Student.B, Student.C]
  Select[Student.D > 30]
    HashJoin[Student.A = Enrolled.A]
      Scan[Student]
      Scan[Enrolled]

=== Plan (optimized) ===
Project[Student.B, Student.C]
  HashJoin[Student.A = Enrolled.A]
    Select[Student.D > 30]
      Scan[Student]
    Project[Enrolled.A]
      Scan[Enrolled]
```

The optimiser pushes the `Select` below the join (selection pushdown) and projects the inner scan down to just the key it needs; the planner picked a hash join for the equi-condition. See the [engine README](engine/README.md#explain) for the full treatment.

## How it works

```
SQL → JSqlParser → QueryPlanner → optimizer → operator tree → results
```

The engine is a from-scratch Volcano/iterator executor — typed values, hash and nested-loop joins, selection pushdown, tuple/time budgets. The server wraps query execution behind a single `QueryService` choke point that applies the budget, a concurrency permit, and audit; **both** the REST query controller and the MCP query tools go through it, so query guardrails can't be bypassed and apply uniformly. Engine internals — architecture, join algorithms, benchmarks, CLI — are in the **[engine README](engine/README.md)**.

## Build and test

```bash
./mvnw clean package    # builds + tests/packages both modules: engine (419 tests) + server (90 tests)
```

The 20 sample queries are a golden-output regression gate (see the engine README to run them). The CI workflow builds, tests, and packages on every push/PR to `main`; the Docker workflow validates image builds on PRs and publishes `ghcr.io/jinba1/cuckoodb` on pushes to `main` and `v*` tags.

## Project structure

```
├── engine/   # pure query engine — Java 17, zero Spring (see engine/README.md)
└── server/   # Spring Boot 4 gateway — REST + MCP over the engine
```

## Background

Originally built as a university project for the Advanced Database Systems course at the University of Edinburgh, then extended into a guarded, agent-facing gateway — REST and MCP interfaces, query budgets, and additional optimisation and test coverage.

## License

Released under the MIT License. See [LICENSE](LICENSE).
