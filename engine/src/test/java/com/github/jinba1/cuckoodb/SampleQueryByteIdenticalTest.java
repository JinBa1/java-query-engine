package com.github.jinba1.cuckoodb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the packaged sample queries through the CLI path and asserts each output is
 * byte-identical to the committed golden output — row ORDER preserved (no sort), with only
 * line endings, per-line trailing whitespace, and trailing newlines normalized.
 *
 * <p>This is the byte gate {@link SampleQueryRunner} performs as a manual {@code exec:exec}
 * driver, now wired into {@code mvn test}/CI. The pre-existing {@code CuckooDBTest} sample
 * check sorts and trims, so it cannot catch a row-ordering or separator regression — exactly
 * what the {@code execute()}-&gt;{@code drain()} refactor must not introduce. This test does
 * not sort, so it does.
 */
class SampleQueryByteIdenticalTest {

    @TempDir
    Path outDir;

    @Test
    void allSampleQueriesAreByteIdentical() throws IOException {
        Path samples = Paths.get(System.getProperty("user.dir"), "samples");
        Path dbDir = samples.resolve("db");
        Path inputDir = samples.resolve("input");
        Path expectedDir = samples.resolve("expected_output");

        File[] queryFiles = inputDir.toFile().listFiles((d, n) -> n.endsWith(".sql"));
        assertNotNull(queryFiles, "sample input directory must exist: " + inputDir);
        assertTrue(queryFiles.length >= 20,
                "expected at least 20 sample queries, found " + queryFiles.length);
        Arrays.sort(queryFiles);

        List<String> failures = new ArrayList<>();
        for (File queryFile : queryFiles) {
            String name = queryFile.getName();
            String base = name.substring(0, name.length() - 4);
            Path expectedFile = expectedDir.resolve(base + ".csv");
            if (!Files.exists(expectedFile)) {
                continue; // no golden output committed for this query
            }
            Path outputFile = outDir.resolve(base + ".csv");

            DBCatalog.resetDBCatalog();
            DBCatalog.initDBCatalog(dbDir.toString());
            PlannedQuery planned = QueryPlanner.planQuery(queryFile.getAbsolutePath());
            if (planned.explainText() != null) {
                Files.writeString(outputFile, planned.explainText());
            } else {
                CuckooDB.execute(planned.root(), outputFile.toString());
            }

            String actual = normalize(Files.readString(outputFile));
            String expected = normalize(Files.readString(expectedFile));
            if (!actual.equals(expected)) {
                failures.add(base);
            }
        }
        assertTrue(failures.isEmpty(), "byte-identical mismatch against golden output in: " + failures);
    }

    /**
     * Mirrors {@link SampleQueryRunner}'s normalization: {@code \n} line endings, per-line
     * trailing-whitespace strip, no trailing newline. Crucially does NOT sort rows, so a
     * row-ordering regression in the CLI path fails this comparison.
     */
    private static String normalize(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines[i].stripTrailing());
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
