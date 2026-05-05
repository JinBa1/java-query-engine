package com.github.jinba1.blazedb.operator;

import com.github.jinba1.blazedb.Tuple;
import com.github.jinba1.blazedb.DBCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * The ScanOperator class is a leaf-level operator within the BlazeDB query execution plan.
 * It performs a full table scan by sequentially reading tuples directly from a database file,
 * following the iterator (Volcano) model.
 * The ScanOperator has no child operators, as it directly interacts with stored database tables.
 * @see Operator
 */
public class ScanOperator extends Operator {

    private final Path tablePath;
    private final String tableName;
    private BufferedReader reader;

    /**
     * Construct a scan operator for the given table.
     * @param tableName The name of the database table this operator scans.
     */
    public ScanOperator(String tableName) {
        this.tableName = tableName;
        tablePath = DBCatalog.getInstance().getDBLocation(tableName);
        child = null; // Scan cannot have child operator

        this.schemaRegistered = true;
        this.intermediateSchemaId = tableName; //Scan never transform schema

        openReader();
    }

    /**
     * Open up a buffered reader for the table this operator scan.
     */
    private void openReader() {
        try {
            reader = Files.newBufferedReader(tablePath);
        } catch (IOException e) {
            System.err.println("Failed to open reader for table " + tableName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the next tuple from the table by reading the next line from the CSV file.
     * If the end of the file is reached, the reader is closed, and null is returned.
     * @return
     */
    @Override
    public Tuple getNextTuple() {
        try {
            String line = reader.readLine();
            if (line == null) { // END OF FILE
                closeReader();
                return null;
            }

            String[] values = line.split(",\\s*"); // parse the line
            ArrayList<Integer> attributes = new ArrayList<>();
            for (String value : values) {
                attributes.add(Integer.parseInt(value.trim()));
            }

            //tupleCounter ++;
            return new Tuple(attributes);
        } catch (IOException e) {
            System.err.println("Error reading tuple: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Resets the ScanOperator state by re-opening the file reader.
     * This method allows the scan to restart iteration from the beginning of the file.
     */
    @Override
    public void reset() {
        try {
            if (reader != null) {
                reader.close();
            }
            openReader();
        } catch (IOException e) {
            System.err.println("Error reading tuple: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Propagates the table name of this ScanOperator for use in schema resolution and query planning.
     * @return The name of the table being scanned.
     */
    @Override
    public String propagateSchemaId() {
        return tableName;
    }

    /**
     * Close the reader used.
     */
    private void closeReader() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing reader for table " + tableName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Clean up resources for the scan operator.
     * Mainly used for unit tests, auto closing is expected normally.
     */
    public void close() {
        closeReader();
    }

    /**
     * Trivial implementation since scan does not alter schema.
     */
    @Override
    protected void registerSchema() {
        this.schemaRegistered = true;
    }
    /**
     * Get the name of the table this operator scans.
     * @return The name of the table.
     */
    public String getTableName() {
        return tableName;
    }
}
