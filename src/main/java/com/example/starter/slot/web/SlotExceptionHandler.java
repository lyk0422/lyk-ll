package com.example.starter.slot.web;

import com.example.starter.slot.exception.FingerprintMismatchException;
import com.example.starter.slot.exception.SlotConflictException;
import com.example.starter.slot.exception.SlotNotFoundException;
import com.example.starter.slot.exception.SlotStateException;
import com.example.starter.slot.exception.SlotValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时刻协调异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class SlotExceptionHandler {

    @ExceptionHandler(SlotConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(SlotConflictException ex, HttpServletRequest request) {
        Map<String, Object> body = body(HttpStatus.CONFLICT, "slot_conflict", ex.getMessage(), request);
        body.put("conflicts", ex.getConflicts());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(FingerprintMismatchException.class)
    public ResponseEntity<Map<String, Object>> fingerprintMismatch(FingerprintMismatchException ex,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "fingerprint_mismatch", ex.getMessage(), request));
    }

    @ExceptionHandler(SlotStateException.class)
    public ResponseEntity<Map<String, Object>> stateConflict(SlotStateException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "state_conflict", ex.getMessage(), request));
    }

    @ExceptionHandler(SlotNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SlotNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), request));
    }

    @ExceptionHandler(SlotValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(SlotValidationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "validation_error", ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("request validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "validation_error", message, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "validation_error", "request body is not readable", request));
    }

    private static Map<String, Object> body(HttpStatus status, String error, String message,
                                            HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
