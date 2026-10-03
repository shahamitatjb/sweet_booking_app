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
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("[ERROR] 400 {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e, HttpServletRequest req) {
        log.warn("[ERROR] 400 {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
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

    /** Spring MVC/security raised errors (404, 405, 415...). Keep Spring's own body. */
    @ExceptionHandler(ErrorResponse.class)
    public void springErrorResponse(ErrorResponse e, HttpServletRequest req,
                                    jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        int status = e.getStatusCode().value();
        String detail = e.getBody().getDetail();
        if (status >= 500) {
            log.error("[ERROR] {} {} -> {} {}", req.getMethod(), req.getRequestURI(), status, detail);
        } else {
            log.warn("[ERROR] {} {} -> {} {}", req.getMethod(), req.getRequestURI(), status, detail);
        }
        res.sendError(status, detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unhandled(Exception e, HttpServletRequest req) {
        log.error("[ERROR] 500 unhandled {} on {} {} — returning generic error",
                e.getClass().getSimpleName(), req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Internal server error — see API logs"));
    }
}
