package com.example.starter.orchestrator.api;

import com.example.starter.orchestrator.CyclicDependencyException;
import com.example.starter.orchestrator.IdempotencyConflictException;
import com.example.starter.orchestrator.InvalidPlanException;
import com.example.starter.orchestrator.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 编排器错误语义到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidPlanException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InvalidPlanException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("INVALID_PLAN", ex.getMessage()));
    }

    @ExceptionHandler(CyclicDependencyException.class)
    public ResponseEntity<Map<String, Object>> handleCycle(CyclicDependencyException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("CYCLIC_DEPENDENCY", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("VALIDATION_FAILED", message));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message == null ? "" : message);
    }
}
