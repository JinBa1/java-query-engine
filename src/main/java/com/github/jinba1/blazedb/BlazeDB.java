package com.github.jinba1.blazedb;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.github.jinba1.blazedb.operator.Operator;

/**
 * Lightweight in-memory relational query engine.
 * CLI interface: database_dir input_file output_file [--max-tuples=N] [--timeout-ms=N]
 */
public class BlazeDB {
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
			PlannedQuery planned = QueryPlanner.planQuery(args[1]);

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
			if (rootOp == null) {
				System.err.println("Error: query could not be planned");
				return 1;
			}
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
		}
	}

	private static void usage() {
		System.err.println(
				"Usage: BlazeDB database_dir input_file output_file [--max-tuples=N] [--timeout-ms=N]");
	}

	/**
	 * Executes the provided query plan by repeatedly calling `getNextTuple()`
	 * on the root object of the operator tree. Writes the result to `outputFile`.
	 * @param root       The root operator of the operator tree (assumed to be non-null).
	 * @param outputFile The name of the file where the result will be written.
	 */
	public static void execute(Operator root, String outputFile) {
		File outputFileObj = new File(outputFile);
		File parentDir = outputFileObj.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			boolean created = parentDir.mkdirs();
			if (!created) {
				System.err.println("Failed to create output directory: " + parentDir.getAbsolutePath());
			}
		}

		List<String> headers = DBCatalog.getInstance().getOrderedColumnNames(root.propagateSchemaId());
		CSVFormat format = CSVFormat.RFC4180.builder().setRecordSeparator("\n").build();
		try {
			try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputFile), format)) {
				printer.printRecord(headers);
				Tuple tuple;
				while ((tuple = root.getNextTuple()) != null) {
					List<String> fields = new ArrayList<>(tuple.getTuple().size());
					for (Value v : tuple.getTuple()) {
						fields.add(v.toString());
					}
					printer.printRecord(fields);
				}
			}
		} catch (QueryExecutionException e) {
			deletePartialOutput(outputFileObj, outputFile);
			throw e;
		} catch (IOException e) {
			// Disk full, permissions, output path is a directory, ... — swallowing this
			// would make a broken or missing file look like success to callers
			deletePartialOutput(outputFileObj, outputFile);
			throw new QueryExecutionException(
					"Failed to write output file '" + outputFile + "': " + e.getMessage());
		}

		System.out.println("Query executed successfully!");
		System.out.println("Output file: " + outputFile);
	}

	/** Never leave a truncated file that looks like a complete result. */
	private static void deletePartialOutput(File outputFileObj, String outputFile) {
		if (outputFileObj.isFile() && !outputFileObj.delete()) {
			System.err.println("Warning: failed to delete partial output: " + outputFile);
		}
	}
}
