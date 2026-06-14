package com.github.jinba1.cuckoodb.server.web;

import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade;
import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;
import com.github.jinba1.cuckoodb.server.web.dto.TableColumnDto;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** The only table names the server will touch; blocks path traversal and odd characters. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

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
        validateName(name);
        List<CatalogFacade.TableColumn> columns = catalog.columnsOf(name);
        if (columns == null) {
            throw new TableNotFoundException(name);
        }
        return new TableSchemaResponse(name, toDto(columns));
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
        validateName(name);
        if (catalog.tableCount() >= properties.upload().maxTables()) {
            throw new TableLimitExceededException(properties.upload().maxTables());
        }

        Path target = resolveSafeTarget(name);
        boolean keep = false;
        try {
            copyWithCap(request.getInputStream(), target, properties.upload().maxBytes());
            // Count rows BEFORE registering: a count failure here must abort cleanly (the finally
            // deletes the temp), never leave the table live in the catalog while the client sees a
            // 500 — that would make the table un-re-uploadable (409) yet reported as failed.
            long rowCount = countDataRows(target);

            boolean registered;
            try {
                registered = catalog.register(name, target);
            } catch (QueryExecutionException e) {
                // Malformed CSV (DATA_ERROR) on the upload path is a client error (400), not a
                // 500; strip any server path from the message before returning it.
                throw new InvalidUploadException(sanitize(e.getMessage(), target));
            }
            if (!registered) {
                throw new TableAlreadyExistsException(name);
            }

            // Registration succeeded; nothing below may throw, so the backing file must survive.
            keep = true;
            return new UploadResponse(name, toDto(catalog.columnsOf(name)), rowCount);
        } finally {
            if (!keep) {
                Files.deleteIfExists(target);
            }
        }
    }

    private void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table name '" + name + "'; must match [A-Za-z0-9_]{1,64}.");
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

    private static long countDataRows(Path csv) throws IOException {
        try (Stream<String> lines = Files.lines(csv)) {
            return Math.max(0, lines.count() - 1); // drop the header row
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

    private static List<TableColumnDto> toDto(List<CatalogFacade.TableColumn> columns) {
        List<TableColumnDto> dtos = new ArrayList<>(columns.size());
        for (CatalogFacade.TableColumn c : columns) {
            dtos.add(new TableColumnDto(c.name(), c.type() == null ? null : c.type().name()));
        }
        return dtos;
    }
}
