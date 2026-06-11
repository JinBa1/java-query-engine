package com.github.jinba1.blazedb;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.github.jinba1.blazedb.operator.Operator;

/**
 * Lightweight in-memory relational query engine.
 * CLI interface: database_dir input_file output_file
 */
public class BlazeDB {
	public static void main(String[] args) {

		if (args.length != 3) {
			System.err.println("Usage: BlazeDB database_dir input_file output_file");
			return;
		}

		String databaseDir = args[0];
		String inputFile = args[1];
		String outputFile = args[2];

		DBCatalog.resetDBCatalog();
		DBCatalog.initDBCatalog(databaseDir);
		Operator rootOp = QueryPlanner.parseStatement(inputFile);
		execute(rootOp, outputFile);
	}

	/**
	 * Executes the provided query plan by repeatedly calling `getNextTuple()`
	 * on the root object of the operator tree. Writes the result to `outputFile`.
	 * @param root       The root operator of the operator tree (assumed to be non-null).
	 * @param outputFile The name of the file where the result will be written.
	 */
	public static void execute(Operator root, String outputFile) {
		try {
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

			System.out.println("Query executed successfully!");
			System.out.println("Output file: " + outputFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
