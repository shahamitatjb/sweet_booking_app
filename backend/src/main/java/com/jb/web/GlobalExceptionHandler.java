package com.jb.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Last-resort handler for exceptions that escape a controller. Every entry point
 * logs the full stack so production incidents can be diagnosed from the API log
 * alone, instead of a bare 500 with no trace.
 *
 * Registered as the most specific handler first, then a catch-all: Spring MVC's
 * own exceptions (404/405/415, implements ErrorResponse) keep their status, and
 * everything else becomes a logged 500.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        warn(e, req);
        return ResponseEntity.badRequest().body(RequestParsing.errorBody(e));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> illegalState(IllegalStateException e, HttpServletRequest req) {
        warn(e, req);
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * Wrapper exceptions ("Excel export failed", "Could not ...") hide the real fault.
     * Print the full cause chain whenever one exists; bare validation messages stay quiet.
     */
    private void warn(Exception e, HttpServletRequest req) {
        if (e.getCause() != null) {
            log.warn("[ERROR] 400 {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage(), e);
        } else {
            log.warn("[ERROR] 400 {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        log.warn("[ERROR] 400 malformed request body on {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request body"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> notValid(MethodArgumentNotValidException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Validation failed");
        log.warn("[ERROR] 400 validation on {} {} : {}", req.getMethod(), req.getRequestURI(), msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unhandled(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            String detail = er.getBody().getDetail();
            if (status >= 500) {
                log.error("[ERROR] {} {} -> {} {}", req.getMethod(), req.getRequestURI(), status, detail, e);
            } else {
                log.warn("[ERROR] {} {} -> {} {}", req.getMethod(), req.getRequestURI(), status, detail);
            }
            return ResponseEntity.status(status).body(Map.of("error", String.valueOf(detail)));
        }
        log.error("[ERROR] 500 unhandled {} on {} {} — returning generic error",
                e.getClass().getSimpleName(), req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Internal server error — see API logs"));
    }
}
