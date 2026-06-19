package com.github.jinba1.cuckoodb.server.web;

import com.github.jinba1.cuckoodb.BudgetKind;
import com.github.jinba1.cuckoodb.ErrorCode;
import com.github.jinba1.cuckoodb.QueryBudgetExceededException;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.server.query.ConcurrencyLimitExceededException;
import com.github.jinba1.cuckoodb.server.web.dto.ErrorResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates every failure into the uniform {@code {errorCode, message}} body with the right
 * HTTP status. Engine {@link ErrorCode}s map to 4xx where the caller can act and 5xx only
 * for genuine engine faults. Two rules protect the client: a budget breach splits by
 * {@link BudgetKind} (tuples → 429+Retry-After, time → 504), and every 5xx returns a generic
 * message plus a correlation {@code errorId} while the full detail (which may embed filesystem
 * paths) is logged server-side only — stack traces are never serialized.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring's own request exceptions (unknown
 * route, wrong method, etc.) keep their correct statuses instead of collapsing into the 500
 * fallback; only the media-type and unreadable-body cases are overridden to use our body shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Retry-After value (seconds) for transient 429s. */
    private static final String RETRY_AFTER_SECONDS = "1";

    /** Budget breach: distinguish a too-big result (429, retry narrower) from too-slow (504). */
    @ExceptionHandler(QueryBudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleBudget(QueryBudgetExceededException ex) {
        ErrorResponse body = ErrorResponse.of(ex.code().name(), ex.getMessage());
        if (ex.kind() == BudgetKind.TIME) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        }
        return retryable(HttpStatus.TOO_MANY_REQUESTS, body);
    }

    /** All other classified engine failures. */
    @ExceptionHandler(QueryExecutionException.class)
    public ResponseEntity<ErrorResponse> handleEngine(QueryExecutionException ex) {
        ErrorCode code = ex.code();
        ErrorResponse verbatim = ErrorResponse.of(code.name(), ex.getMessage());
        return switch (code) {
            case PARSE_ERROR -> ResponseEntity.badRequest().body(verbatim);
            case UNSUPPORTED_SQL, UNKNOWN_TABLE, UNKNOWN_COLUMN, TYPE_MISMATCH ->
                    ResponseEntity.unprocessableEntity().body(verbatim);
            // A bare BUDGET_EXCEEDED (not the kinded subclass) is treated as the tuples case.
            case BUDGET_EXCEEDED -> retryable(HttpStatus.TOO_MANY_REQUESTS, verbatim);
            // Query-path data/internal faults can embed absolute paths — scrub to a generic 500.
            case DATA_ERROR, INTERNAL -> internal(code, ex);
        };
    }

    /** Concurrency saturation: transient, so 429 + Retry-After. */
    @ExceptionHandler(ConcurrencyLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleConcurrency(ConcurrencyLimitExceededException ex) {
        return retryable(HttpStatus.TOO_MANY_REQUESTS,
                ErrorResponse.of("CONCURRENCY_LIMIT", ex.getMessage()));
    }

    @ExceptionHandler(TableNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTableNotFound(TableNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("UNKNOWN_TABLE", ex.getMessage()));
    }

    @ExceptionHandler(UploadDisabledException.class)
    public ResponseEntity<ErrorResponse> handleUploadDisabled(UploadDisabledException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TableAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTableExists(TableAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("TABLE_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("PAYLOAD_TOO_LARGE", ex.getMessage()));
    }

    @ExceptionHandler(TableLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleTableLimit(TableLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                .body(ErrorResponse.of("TABLE_LIMIT", ex.getMessage()));
    }

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUpload(InvalidUploadException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("DATA_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", ex.getMessage()));
    }

    /** Anything unclassified is an engine/server bug: scrub the body, log the detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception errorId={}", errorId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.withId("INTERNAL", "Internal server error.", errorId));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // This advice is global, so the hint must name THIS endpoint's accepted types (text/csv
        // for upload, application/json for /queries) rather than a hardcoded guess.
        List<MediaType> supported = ex.getSupportedMediaTypes();
        String message = supported.isEmpty()
                ? "Unsupported Content-Type for this endpoint."
                : "Unsupported Content-Type; this endpoint expects "
                        + supported.stream().map(MediaType::toString).collect(Collectors.joining(", "))
                        + ".";
        return new ResponseEntity<>(ErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", message),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(
                ErrorResponse.of("BAD_REQUEST", "Malformed or missing request body."),
                HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ErrorResponse> internal(ErrorCode code, Exception ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("5xx [{}] errorId={}", code, errorId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.withId(code.name(), "Internal server error.", errorId));
    }

    private static ResponseEntity<ErrorResponse> retryable(HttpStatus status, ErrorResponse body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }
}
