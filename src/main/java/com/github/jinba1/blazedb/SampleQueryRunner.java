package com.github.jinba1.blazedb;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs all 20 sample queries and diffs output against expected results.
 * Exit code 0 if all pass, 1 if any fail.
 */
public class SampleQueryRunner {

    private static final int QUERY_COUNT = 20;

    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get("").toAbsolutePath();
        Path dbDir = projectRoot.resolve("samples/db");
        Path inputDir = projectRoot.resolve("samples/input");
        Path expectedDir = projectRoot.resolve("samples/expected_output");
        Path tempDir = Files.createTempDirectory("blazedb-test-");

        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 1; i <= QUERY_COUNT; i++) {
            String queryName = "query" + i;
            Path queryFile = inputDir.resolve(queryName + ".sql");
            Path expectedFile = expectedDir.resolve(queryName + ".csv");
            Path outputFile = tempDir.resolve(queryName + ".csv");

            System.out.print(queryName + ": ");

            try {
                DBCatalog.resetDBCatalog();
                DBCatalog.initDBCatalog(dbDir.toString());
                PlannedQuery planned = QueryPlanner.planQuery(queryFile.toString());
                if (planned.explainText() != null) {
                    Files.writeString(outputFile, planned.explainText());
                } else {
                    BlazeDB.execute(planned.root(), outputFile.toString());
                }

                String actual = normalize(readFile(outputFile));
                String expected = normalize(readFile(expectedFile));

                if (actual.equals(expected)) {
                    System.out.println("PASS");
                    passed++;
                } else {
                    System.out.println("FAIL");
                    failures.add(queryName);
                    String[] actualLines = actual.split("\n");
                    String[] expectedLines = expected.split("\n");
                    int maxLines = Math.max(actualLines.length, expectedLines.length);
                    for (int line = 0; line < maxLines; line++) {
                        String aLine = line < actualLines.length ? actualLines[line] : "<EOF>";
                        String eLine = line < expectedLines.length ? expectedLines[line] : "<EOF>";
                        if (!aLine.equals(eLine)) {
                            System.out.println("  First diff at line " + (line + 1));
                            System.out.println("    Expected: " + eLine);
                            System.out.println("    Actual:   " + aLine);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("FAIL (exception)");
                failures.add(queryName);
                e.printStackTrace(System.out);
            }
        }

        try {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}

        System.out.println();
        System.out.println(passed + "/" + QUERY_COUNT + " PASSED");

        if (passed < QUERY_COUNT) {
            System.out.println("Failed: " + String.join(", ", failures));
            System.exit(1);
        }
    }

    private static String readFile(Path file) throws IOException {
        return new String(Files.readAllBytes(file));
    }

    private static String normalize(String content) {
        // Normalize line endings to \n, trim trailing whitespace per line, remove trailing newline
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (i > 0) sb.append("\n");
            sb.append(line);
        }
        // Remove trailing empty lines
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
