package com.example.starter.costmap.web;

import com.example.starter.costmap.ConflictException;
import com.example.starter.costmap.CostmapException;
import com.example.starter.costmap.InvalidRegionException;
import com.example.starter.costmap.VersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将业务异常映射为稳定错误结构：{@code {timestamp, status, error, code, message}}。
 */
@RestControllerAdvice
public class CostmapExceptionHandler {

    @ExceptionHandler(InvalidRegionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRegion(CostmapException ex) {
        return build(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(CostmapException ex) {
        return build(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(CostmapException ex) {
        return build(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("validation failed");
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "request body is missing or malformed");
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, CostmapException ex) {
        return ResponseEntity.status(status).body(baseBody(status, ex.code(), ex.getMessage()));
    }

    private Map<String, Object> baseBody(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toInstant().toEpochMilli());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
