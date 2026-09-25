package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.ValidationErrorResponse;
import com.example.starter.rolling.service.BusinessException;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务异常到 HTTP 状态码的统一映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(ValidationException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ValidationErrorResponse("SCHEDULE_VIOLATION", ex.getMessage(), ex.getViolations()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("code", "CONFLICT", "message", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("code", "BUSINESS_ERROR", "message", ex.getMessage()));
    }
}
