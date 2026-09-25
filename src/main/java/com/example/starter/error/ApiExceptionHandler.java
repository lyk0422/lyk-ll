package com.example.starter.error;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误语义：404 资源不存在，409 幂等冲突/状态冲突/并发受理冲突，422 业务规则拒绝。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 资源不存在，HTTP 404。
     */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 幂等冲突：同一 requestId 携带不同业务参数，HTTP 409。
     */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    /**
     * 状态冲突：非法状态流转、单人重复核对、并发日检唯一受理冲突、终态后重复操作等，HTTP 409。
     */
    public static class StateConflictException extends RuntimeException {
        public StateConflictException(String message) {
            super(message);
        }
    }

    /**
     * 业务规则拒绝：偏差超限、活度勾稽不等，HTTP 422，body 携带业务明细。
     */
    public static class BusinessRejectionException extends RuntimeException {
        private final Map<String, Object> details;

        public BusinessRejectionException(String message, Map<String, Object> details) {
            super(message);
            this.details = details;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> idempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(StateConflictException.class)
    public ResponseEntity<Map<String, Object>> stateConflict(StateConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(BusinessRejectionException.class)
    public ResponseEntity<Map<String, Object>> businessRejected(BusinessRejectionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", e.getMessage(), "details", e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数校验失败"));
    }
}
