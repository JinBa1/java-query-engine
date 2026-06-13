package com.github.jinba1.cuckoodb;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.github.jinba1.cuckoodb.operator.LimitOperator;
import com.github.jinba1.cuckoodb.operator.Operator;

/**
 * Lightweight in-memory relational query engine.
 * CLI interface: database_dir input_file output_file [--max-tuples=N] [--timeout-ms=N]
 */
public class CuckooDB {
	public static void main(String[] args) {
		int code = run(args);
		if (code != 0) {
			System.exit(code);
		}
	}

	/**
	 * CLI entry minus System.exit, so tests can call it directly.
	 * @return process exit code: 0 on success, 1 on any error
	 */
	static int run(String[] args) {
		if (args.length < 3) {
			usage();
			return 1;
		}

		Long maxTuples = null;
		Long timeoutMs = null;
		for (int i = 3; i < args.length; i++) {
			String arg = args[i];
			try {
				if (arg.startsWith("--max-tuples=")) {
					maxTuples = Long.parseLong(arg.substring("--max-tuples=".length()));
				} else if (arg.startsWith("--timeout-ms=")) {
					timeoutMs = Long.parseLong(arg.substring("--timeout-ms=".length()));
				} else {
					System.err.println("Unknown option: " + arg);
					usage();
					return 1;
				}
			} catch (NumberFormatException e) {
				System.err.println("Invalid number in option: " + arg);
				usage();
				return 1;
			}
		}
		if ((maxTuples != null && maxTuples < 0) || (timeoutMs != null && timeoutMs < 0)) {
			System.err.println("Budget values must be non-negative");
			usage();
			return 1;
		}

		try {
			DBCatalog.resetDBCatalog();
			DBCatalog.initDBCatalog(args[0]);
			PlannedQuery planned = QueryPlanner.planQuery(args[1], QueryConfig.defaults());

			if (planned.explainText() != null) {
				// EXPLAIN: write the plan text, execute nothing; budgets don't apply
				Path explainOut = Path.of(args[2]);
				if (explainOut.getParent() != null) {
					Files.createDirectories(explainOut.getParent());
				}
				Files.writeString(explainOut, planned.explainText());
				System.out.println("Explain written to: " + args[2]);
				return 0;
			}

			Operator rootOp = planned.root();
			if (maxTuples != null || timeoutMs != null) {
				rootOp.attachBudget(new QueryBudget(maxTuples, timeoutMs));
			}
			execute(rootOp, args[2]);
			return 0;
		} catch (QueryExecutionException e) {
			System.err.println("Error: " + e.getMessage());
			return 1;
		} catch (IOException e) {
			System.err.println("Error: failed to write output: " + e.getMessage());
			return 1;
		} catch (RuntimeException e) {
			// Engine bug, not a user error — fail with an exit code instead of an
			// uncaught-exception crash; the trace stays on stderr for bug reports
			System.err.println("Internal error: " + e);
			e.printStackTrace();
			return 1;
		}
	}

	private static void usage() {
		System.err.println(
				"Usage: CuckooDB database_dir input_file output_file [--max-tuples=N] [--timeout-ms=N]");
	}

	/**
	 * Executes the provided query plan by repeatedly calling `getNextTuple()`
	 * on the root object of the operator tree. Writes the result to `outputFile`.
	 * @param root       The root operator of the operator tree (assumed to be non-null).
	 * @param outputFile The name of the file where the result will be written.
	 * @return Execution metadata: rows written, LIMIT truncation, refine hint.
	 */
	public static QueryResult execute(Operator root, String outputFile) {
		File outputFileObj = new File(outputFile);
		File parentDir = outputFileObj.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			boolean created = parentDir.mkdirs();
			if (!created) {
				System.err.println("Failed to create output directory: " + parentDir.getAbsolutePath());
			}
		}

		List<String> headers = root.getContext().getOrderedColumnNames(root.propagateSchemaId());
		CSVFormat format = CSVFormat.RFC4180.builder().setRecordSeparator("\n").build();
		long rows = 0;
		try {
			try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputFile), format)) {
				printer.printRecord(headers);
				rows = drain(root, tuple -> {
					List<String> fields = new ArrayList<>(tuple.getTuple().size());
					for (Value v : tuple.getTuple()) {
						fields.add(v.toString());
					}
					printer.printRecord(fields);
				});
			}
		} catch (RuntimeException e) {
			// QueryExecutionException and internal errors alike: never leave a
			// truncated file that looks like a complete result
			deletePartialOutput(outputFileObj, outputFile);
			throw e;
		} catch (IOException e) {
			// Disk full, permissions, output path is a directory, ... — swallowing this
			// would make a broken or missing file look like success to callers
			deletePartialOutput(outputFileObj, outputFile);
			throw new QueryExecutionException(ErrorCode.DATA_ERROR,
					"Failed to write output file '" + outputFile + "': " + e.getMessage());
		}

		boolean truncated = wasTruncated(root);
		QueryResult result = truncated ? QueryResult.truncated(rows) : QueryResult.complete(rows);

		System.out.println("Query executed successfully!");
		System.out.println("Output file: " + outputFile);
		System.out.println("Rows: " + rows + (truncated ? " (truncated; more rows exist)" : ""));
		return result;
	}

	/**
	 * Drains the plan into an in-memory result set — column metadata, positional rows, and the
	 * same truncation/hint signal {@link #execute} reports, but with no stdout and no file write.
	 * This is the library/REST entry point; the CLI keeps using {@link #execute}. Both share
	 * {@link #drain}, so they cannot diverge in iteration, row count, or truncation semantics.
	 * @param root the root operator of an executable plan (never null; EXPLAIN is handled before here)
	 * @return the fully-materialized result
	 */
	public static QueryResultSet executeToResultSet(Operator root) {
		String schemaId = root.propagateSchemaId();
		List<String> names = root.getContext().getOrderedColumnNames(schemaId);
		List<List<Value>> rows = new ArrayList<>();
		try {
			drain(root, tuple -> rows.add(List.copyOf(tuple.getTuple())));
		} catch (IOException e) {
			// The in-memory sink does no I/O; drain only declares IOException for the CSV path.
			throw new QueryExecutionException(ErrorCode.INTERNAL,
					"Unexpected I/O while draining query result: " + e.getMessage());
		}
		boolean truncated = wasTruncated(root);
		String hint = truncated ? QueryResult.truncated(rows.size()).hint() : null;
		List<ColumnMeta> columns = buildColumns(root.getContext(), schemaId, names, rows);
		// The result is a value handed to library/REST callers; the outer lists are
		// unmodifiable so a consumer cannot mutate the result after the fact (each inner
		// row is already immutable via List.copyOf in the drain sink).
		return new QueryResultSet(Collections.unmodifiableList(columns),
				Collections.unmodifiableList(rows), truncated, hint);
	}

	/**
	 * Pulls every tuple from the plan to EOF, handing each to {@code sink}, and returns the row
	 * count. The single drain point for both result paths: truncation can only be read once this
	 * has run to a null tuple, so sharing it keeps the file and in-memory paths consistent.
	 */
	private static long drain(Operator root, TupleSink sink) throws IOException {
		long rows = 0;
		Tuple tuple;
		while ((tuple = root.getNextTuple()) != null) {
			sink.accept(tuple);
			rows++;
		}
		return rows;
	}

	/** A per-tuple action that may fail with an I/O error (the CSV writer's printRecord does). */
	@FunctionalInterface
	private interface TupleSink {
		void accept(Tuple tuple) throws IOException;
	}

	/**
	 * Whether a LIMIT cut the result short. The planner places LIMIT topmost, so only the root
	 * can report this, and only after the drain reached EOF (the peek past the cap has happened).
	 */
	private static boolean wasTruncated(Operator root) {
		return root instanceof LimitOperator limitOp && limitOp.wasTruncated();
	}

	/**
	 * Builds per-position column metadata for a result set. {@code name} is the bare header
	 * (table prefix stripped, aggregate keys kept whole); {@code qualifiedName} is the dotted,
	 * non-aggregate schema key for that index when one exists (null for single-table scans and
	 * computed columns); {@code type} is inferred from the first row, or null for an empty result.
	 */
	private static List<ColumnMeta> buildColumns(PlanContext ctx, String schemaId,
			List<String> names, List<List<Value>> rows) {
		int width = names.size();
		String[] qualified = new String[width];
		Map<String, Integer> schema = ctx.getSchema(schemaId);
		if (schema != null) {
			// Mirror getOrderedColumnNames' deterministic rule — sorted keys, first non-null
			// wins per index — so `name` and `qualifiedName` are chosen from a single key
			// choice and never disagree run-to-run. Skip internal intermediate-schema keys
			// (temp_<hex>.col) outright: a join over a pushed-down source registers BOTH a
			// base-qualified key (Enrolled.a) and a temp_ key at the same index, and the
			// temp_ id is not a real origin (and is plan/JVM-unstable).
			List<String> keys = new ArrayList<>(schema.keySet());
			Collections.sort(keys);
			for (String key : keys) {
				int idx = schema.get(key);
				if (idx < 0 || idx >= width || qualified[idx] != null
						|| key.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
					continue;
				}
				// A dotted, non-aggregate key (e.g. "Student.a") is the column's qualified
				// origin. Aggregate keys like "sum(student.c)" also contain '.', so the '('
				// check excludes them. Lowercased to match the bare name (-> "student.a").
				if (key.indexOf('.') >= 0 && key.indexOf('(') < 0) {
					qualified[idx] = key.toLowerCase();
				}
			}
		}
		List<Value> firstRow = rows.isEmpty() ? null : rows.get(0);
		List<ColumnMeta> columns = new ArrayList<>(width);
		for (int i = 0; i < width; i++) {
			ColumnType type = firstRow == null ? null : inferType(firstRow.get(i));
			columns.add(new ColumnMeta(names.get(i), qualified[i], type));
		}
		return columns;
	}

	/** Maps a runtime value to its column type. Exhaustive over the sealed {@link Value}. */
	private static ColumnType inferType(Value value) {
		if (value instanceof IntValue) {
			return ColumnType.INT;
		} else if (value instanceof StringValue) {
			return ColumnType.STRING;
		}
		throw new QueryExecutionException(ErrorCode.INTERNAL,
				"Unknown value type: " + value.getClass().getName());
	}

	/** Never leave a truncated file that looks like a complete result. */
	private static void deletePartialOutput(File outputFileObj, String outputFile) {
		if (outputFileObj.isFile() && !outputFileObj.delete()) {
			System.err.println("Warning: failed to delete partial output: " + outputFile);
		}
	}
}
