package com.example.starter.ledger.web;

import com.example.starter.ledger.service.ChainMismatchException;
import com.example.starter.ledger.service.ConflictException;
import com.example.starter.ledger.service.NotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 状态码的统一映射。
 */
@RestControllerAdvice
public class LedgerExceptionHandler {

    /** 资源不存在 → 404。 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("NOT_FOUND", e.getMessage()));
    }

    /** 业务冲突（含同键异参、在途约束、重复核销） → 409。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorBody> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("CONFLICT", e.getMessage()));
    }

    /** 事件链不一致 → 422。 */
    @ExceptionHandler(ChainMismatchException.class)
    public ResponseEntity<ErrorBody> handleChainMismatch(ChainMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("CHAIN_MISMATCH", e.getMessage()));
    }

    /** 参数校验失败 → 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("BAD_REQUEST", message));
    }

    /** 错误响应体。 */
    public record ErrorBody(String error, String message, Instant timestamp) {
        ErrorBody(String error, String message) {
            this(error, message, Instant.now());
        }
    }
}
