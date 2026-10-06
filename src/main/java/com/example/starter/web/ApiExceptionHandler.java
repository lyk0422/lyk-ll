package com.example.starter.web;

import com.example.starter.error.BadRequestException;
import com.example.starter.error.BusinessConflictException;
import com.example.starter.error.IdempotencyConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.web.dto.ApiDtos.ErrorResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误语义：404 资源不存在 / 400 参数非法 / 409 幂等或状态冲突。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        String message = e instanceof MethodArgumentNotValidException manve
                ? "请求参数校验失败: " + manve.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                        .findFirst().orElse("invalid")
                : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", message));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> idempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler({BusinessConflictException.class, DuplicateKeyException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", e.getMessage()));
    }
}
