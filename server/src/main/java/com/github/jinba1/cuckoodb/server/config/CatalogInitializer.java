package com.github.jinba1.cuckoodb.server.config;

import com.github.jinba1.cuckoodb.DBCatalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the catalog lifecycle under Spring. The engine's {@link DBCatalog} is a static
 * singleton whose {@code initDBCatalog} silently no-ops a second call and whose
 * {@code resetDBCatalog} nulls the instance with no reader coordination — both fight Spring's
 * (and the test harness's) context lifecycle.
 *
 * <p>So this runner performs the one deterministic seeding for a context: {@code reset} then
 * {@code init}, exactly once, before any request can arrive. At production boot the reset is a
 * no-op (the singleton starts null); in tests it gives each freshly-built context a catalog
 * seeded purely from its own configured {@code data-dir}, with no leakage from a prior
 * context's singleton. Integration tests therefore must NOT call {@code resetDBCatalog}
 * themselves and should isolate contexts with {@code @DirtiesContext}.
 */
@Component
public class CatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogInitializer.class);

    private final CuckooDbProperties properties;

    public CatalogInitializer(CuckooDbProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path workDir = Path.of(properties.workDir());
        Files.createDirectories(workDir);
        log.info("Upload work dir: {}", workDir.toAbsolutePath());

        DBCatalog.resetDBCatalog();
        String dataDir = properties.dataDir();
        if (dataDir != null && !dataDir.isBlank()) {
            DBCatalog.initDBCatalog(dataDir);
            log.info("Catalog seeded from data dir '{}': {} table(s)",
                    dataDir, DBCatalog.getInstance().getTableNames().size());
        } else {
            // No seed dir: create an empty catalog so registerTable has an instance to write to.
            DBCatalog.getInstance();
            log.info("No cuckoodb.data-dir configured; catalog starts empty (uploads only)");
        }
    }
}
