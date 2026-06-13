package com.github.jinba1.cuckoodb.operator;

import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.ErrorCode;
import com.github.jinba1.cuckoodb.IntValue;
import com.github.jinba1.cuckoodb.PlanContext;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.StringValue;
import com.github.jinba1.cuckoodb.Tuple;
import com.github.jinba1.cuckoodb.Value;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The ScanOperator class is a leaf-level operator within the CuckooDB query execution plan.
 * It performs a full table scan by sequentially reading tuples directly from a database file,
 * following the iterator (Volcano) model.
 * The ScanOperator has no child operators, as it directly interacts with stored database tables.
 * @see Operator
 */
public class ScanOperator extends Operator {

    private final Path tablePath;
    private final String tableName;
    private final List<ColumnType> columnTypes;
    private CSVParser parser;
    private Iterator<CSVRecord> records;

    /**
     * Construct a scan operator for the given table.
     * @param tableName The name of the database table this operator scans.
     */
    public ScanOperator(PlanContext ctx, String tableName) {
        super(ctx);
        this.tableName = tableName;
        tablePath = DBCatalog.getInstance().getDBLocation(tableName);
        this.columnTypes = DBCatalog.getInstance().getColumnTypes(tableName);
        if (tablePath == null || columnTypes == null) {
            throw DBCatalog.getInstance().unknownTable(tableName);
        }
        child = null; // Scan cannot have child operator

        this.schemaRegistered = true;
        this.intermediateSchemaId = tableName; //Scan never transform schema

        openReader();
    }

    /**
     * Open up a CSVParser for the table this operator scans, skipping the header row.
     */
    private void openReader() {
        try {
            CSVFormat format = CSVFormat.RFC4180.builder()
                    .setIgnoreSurroundingSpaces(true)
                    .build();
            parser = CSVParser.parse(tablePath, StandardCharsets.UTF_8, format);
            records = parser.iterator();
            if (records.hasNext()) {
                records.next(); // skip header row
            }
        } catch (IOException e) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Failed to open table '" + tableName + "': " + e.getMessage());
        }
    }

    /**
     * Retrieves the next tuple from the table by reading the next CSV record.
     * If the end of the file is reached, the parser is closed, and null is returned.
     * @return The next Tuple, or null if the end of file has been reached.
     */
    @Override
    public Tuple getNextTuple() {
        if (!records.hasNext()) {
            closeReader();
            return null;
        }
        CSVRecord record = records.next();
        if (record.size() != columnTypes.size()) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Table '" + tableName + "' row " + record.getRecordNumber()
                    + ": expected " + columnTypes.size() + " fields, found " + record.size());
        }
        List<Value> attributes = new ArrayList<>(columnTypes.size());
        for (int i = 0; i < columnTypes.size(); i++) {
            String field = record.get(i);
            if (columnTypes.get(i) == ColumnType.INT) {
                try {
                    attributes.add(new IntValue(Integer.parseInt(field)));
                } catch (NumberFormatException e) {
                    throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                            "Table '" + tableName + "' row " + record.getRecordNumber()
                            + ", column " + i + ": expected int, found '" + field
                            + "' (file changed since catalog init?)");
                }
            } else {
                attributes.add(new StringValue(field));
            }
        }
        countTuple();
        return new Tuple(attributes);
    }

    /**
     * Resets the ScanOperator state by re-opening the file reader.
     * This method allows the scan to restart iteration from the beginning of the file.
     */
    @Override
    public void reset() {
        closeReader();
        openReader();
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
     * Close the parser used.
     */
    private void closeReader() {
        try {
            if (parser != null && !parser.isClosed()) {
                parser.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing parser for table " + tableName + ": " + e.getMessage());
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

    @Override
    public String describe() {
        return "Scan[" + tableName + "]";
    }

    /**
     * Get the name of the table this operator scans.
     * @return The name of the table.
     */
    public String getTableName() {
        return tableName;
    }
}
