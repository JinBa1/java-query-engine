package com.github.jinba1.cuckoodb.server.web;

import com.github.jinba1.cuckoodb.CsvFormats;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade;
import com.github.jinba1.cuckoodb.server.catalog.CatalogMapper;
import com.github.jinba1.cuckoodb.server.catalog.TableNameValidator;
import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;
import com.github.jinba1.cuckoodb.server.web.dto.TableSchemaResponse;
import com.github.jinba1.cuckoodb.server.web.dto.UploadResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.csv.CSVParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Table catalog endpoints: list tables, describe a table's static typed schema, and (opt-in)
 * upload a CSV as a process-lifetime table. The server is the <em>only</em> guard on the table
 * name — the engine and {@code ScanOperator} use it verbatim — so every name is validated
 * against a strict charset before any filesystem path is built, and the resolved path is
 * asserted to stay within the work directory.
 */
@RestController
@RequestMapping("/tables")
public class TableController {

    private final CatalogFacade catalog;
    private final CuckooDbProperties properties;

    public TableController(CatalogFacade catalog, CuckooDbProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    /** {@code GET /tables} — all registered table names, sorted. */
    @GetMapping
    public List<String> list() {
        return catalog.tableNames();
    }

    /** {@code GET /tables/{name}} — a table's static, catalog-authoritative typed schema. */
    @GetMapping("/{name}")
    public TableSchemaResponse describe(@PathVariable String name) {
        TableNameValidator.validate(name);
        List<CatalogFacade.TableColumn> columns = catalog.columnsOf(name)
                .orElseThrow(() -> new TableNotFoundException(name));
        return new TableSchemaResponse(name, CatalogMapper.toDto(columns));
    }

    /**
     * {@code POST /tables/{name}} (text/csv) — register a CSV as a new table. Disabled by
     * default (404 when off). Enforces, in order: name charset, table-count cap, streamed size
     * cap, malformed-CSV rejection, and an atomic 409 on a name clash. The winning request's
     * temp file becomes the table's permanent backing file (a losing or failing request deletes
     * its temp), because the engine re-opens that file on every scan.
     */
    @PostMapping(value = "/{name}", consumes = "text/csv")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@PathVariable String name, HttpServletRequest request)
            throws IOException {
        if (!properties.upload().enabled()) {
            throw new UploadDisabledException();
        }
        TableNameValidator.validate(name);
        int maxTables = properties.upload().maxTables();
        // Cheap pre-check to reject an over-cap upload before streaming its body; the authoritative
        // check is re-done atomically with the register below, so this is only an optimisation.
        if (catalog.tableCount() >= maxTables) {
            throw new TableLimitExceededException(maxTables);
        }

        Path target = resolveSafeTarget(name);
        boolean keep = false;
        try {
            copyWithCap(request.getInputStream(), target, properties.upload().maxBytes());
            // Count rows BEFORE registering: a count failure here must abort cleanly (the finally
            // deletes the temp), never leave the table live in the catalog while the client sees a
            // 500 — that would make the table un-re-uploadable (409) yet reported as failed.
            long rowCount = countDataRows(target);

            CatalogFacade.RegistrationOutcome outcome;
            try {
                outcome = catalog.registerIfUnderCap(name, target, maxTables);
            } catch (QueryExecutionException e) {
                // Malformed CSV (DATA_ERROR) on the upload path is a client error (400), not a
                // 500; strip any server path from the message before returning it.
                throw new InvalidUploadException(sanitize(e.getMessage(), target));
            }
            // The cap re-check ran under the catalog lock, so a race winner that pushed us to the
            // ceiling between the pre-check and here is caught: 507, no overshoot.
            if (outcome == CatalogFacade.RegistrationOutcome.OVER_CAP) {
                throw new TableLimitExceededException(maxTables);
            }
            if (outcome == CatalogFacade.RegistrationOutcome.NAME_TAKEN) {
                throw new TableAlreadyExistsException(name);
            }

            // Registration succeeded; nothing below may throw, so the backing file must survive.
            keep = true;
            List<CatalogFacade.TableColumn> columns = catalog.columnsOf(name).orElseThrow(
                    () -> new IllegalStateException("Table '" + name + "' vanished after register"));
            return new UploadResponse(name, CatalogMapper.toDto(columns), rowCount);
        } finally {
            if (!keep) {
                Files.deleteIfExists(target);
            }
        }
    }

    /** Builds a work-dir-relative target and proves it cannot escape that directory. */
    private Path resolveSafeTarget(String name) {
        Path base = Path.of(properties.workDir()).toAbsolutePath().normalize();
        Path target = base.resolve(name + "-" + UUID.randomUUID() + ".csv").normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("Resolved upload path escapes the work directory.");
        }
        return target;
    }

    /**
     * Streams the request body to {@code target}, aborting as soon as the byte count exceeds
     * {@code maxBytes}, so an oversized upload is never fully buffered. The partial file is
     * cleaned up by the caller's finally block.
     */
    private static void copyWithCap(InputStream in, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Data-row count for the upload response. Parses with the engine's exact CSV dialect
     * ({@link CsvFormats#TABLE}) rather than counting physical lines, so a quoted field containing
     * a newline is one record and the count agrees with what {@code SELECT COUNT(*)} would scan —
     * including whitespace-padded multiline quoted fields, which only match when the same
     * {@code ignoreSurroundingSpaces} dialect is used.
     */
    private static long countDataRows(Path csv) throws IOException {
        try (CSVParser parser = CSVParser.parse(csv, StandardCharsets.UTF_8, CsvFormats.TABLE)) {
            return Math.max(0, parser.stream().count() - 1); // first record is the header row
        }
    }

    private static String sanitize(String message, Path target) {
        if (message == null) {
            return "Malformed CSV upload.";
        }
        // target is already absolute (resolveSafeTarget normalises against an absolute base),
        // so target.toString() is the exact path the engine embeds in its messages.
        return message.replace(target.toString(), "<upload>");
    }
}
