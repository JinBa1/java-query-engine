package com.github.jinba1.cuckoodb.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Shared test helpers for temp-directory fixtures used by the integration tests. */
public final class TestFiles {

    private TestFiles() {
    }

    /** Best-effort recursive delete of a temp tree; tolerates an already-gone root or files. */
    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }
}
