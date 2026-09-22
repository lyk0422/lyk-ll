package com.example.starter.orchestrator;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 编排 API 异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(PlanNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({PlanConflictException.class, PlanStateException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CycleDependencyException.class)
    public ResponseEntity<Map<String, Object>> cycle(CycleDependencyException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage(), "cyclePath", e.getCyclePath()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数校验失败"));
    }
}
