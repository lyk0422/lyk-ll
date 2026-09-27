package com.example.starter.radio.web;

import com.example.starter.radio.service.BusinessException;
import com.example.starter.radio.service.ConflictException;
import com.example.starter.radio.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误语义：409 幂等/并发冲突，422 业务规则拒绝，404 资源不存在，400 参数非法。
 */
@RestControllerAdvice
public class RadioExceptionHandler {

    /** 错误响应体。 */
    public record ErrorBody(String error) {
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorBody> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorBody> business(BusinessException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,
            java.time.format.DateTimeParseException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(e.getMessage()));
    }
}
