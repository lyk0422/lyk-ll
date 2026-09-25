package com.example.starter.slot;

import com.example.starter.slot.dto.ErrorView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常到错误响应的映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SlotConflictException.class)
    public ResponseEntity<ErrorView> handleConflict(SlotConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView(ex.getCode(), ex.getMessage(), ex.getConflicts()));
    }

    @ExceptionHandler(PeriodFrozenException.class)
    public ResponseEntity<ErrorView> handleFrozen(PeriodFrozenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorView.of("PERIOD_FROZEN", ex.getMessage()));
    }

    @ExceptionHandler(SlotNotFoundException.class)
    public ResponseEntity<ErrorView> handleNotFound(SlotNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorView.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorView> handleBadRequest(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException manve
                && manve.getBindingResult().getFieldError() != null
                ? manve.getBindingResult().getFieldError().getField() + ": "
                + manve.getBindingResult().getFieldError().getDefaultMessage()
                : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorView.of("VALIDATION_FAILED", message));
    }
}
