package com.github.jinba1.blazedb;

import java.io.*;

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

			// Ensure the output directory exists
			File outputFileObj = new File(outputFile);
			File parentDir = outputFileObj.getParentFile();
			if (parentDir != null && !parentDir.exists()) {
				boolean created = parentDir.mkdirs();
				if (!created) {
					System.err.println("Failed to create output directory: " + parentDir.getAbsolutePath());
				}
			}

			// Create a BufferedWriter
			BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

			// Iterate over the tuples produced by root
			Tuple tuple = root.getNextTuple();
			while (tuple != null) {
				writer.write(tuple.toString());
				writer.newLine();
				tuple = root.getNextTuple();
			}
			// Close the writer
			writer.close();

			System.out.println("Query executed successfully!");
			System.out.println("Output file: " + outputFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
