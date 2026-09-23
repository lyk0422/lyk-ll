package com.example.starter.token.api;

import com.example.starter.token.TokenServiceException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class TokenExceptionHandler {

    @ExceptionHandler(TokenServiceException.class)
    public ResponseEntity<Map<String, Object>> handle(TokenServiceException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", ex.status(),
                        "error", HttpStatus.valueOf(ex.status()).getReasonPhrase(),
                        "message", ex.getMessage()));
    }
}
