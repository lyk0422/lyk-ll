package com.example.starter.tf.api;

import com.example.starter.tf.domain.TfException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将业务异常与参数校验异常映射为统一错误响应。
 */
@RestControllerAdvice
public class TfExceptionHandler {

    @ExceptionHandler(TfException.class)
    public ResponseEntity<ErrorResponse> handleTf(TfException ex) {
        Map<String, Object> details = null;
        if (ex instanceof TfException.CycleDetected cycle) {
            details = Map.of("cycle", cycle.cycle());
        }
        return ResponseEntity.status(ex.httpStatus())
                .body(new ErrorResponse(ex.code(), ex.getMessage(), details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", ex.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", ex.getMessage(), null));
    }
}
