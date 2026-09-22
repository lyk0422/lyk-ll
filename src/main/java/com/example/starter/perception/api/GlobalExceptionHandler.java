package com.example.starter.perception.api;

import com.example.starter.perception.error.BusinessRuleException;
import com.example.starter.perception.error.TrackNotFoundException;
import com.example.starter.perception.error.VersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一错误响应：{ timestamp, status, error, reason, message }。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TrackNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(TrackNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TRACK_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(VersionConflictException ex) {
        return build(HttpStatus.CONFLICT, "VERSION_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessRuleException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getReason(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("reason", reason);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
