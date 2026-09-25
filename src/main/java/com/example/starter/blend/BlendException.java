package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.ErrorBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 调合服务业务异常与统一错误语义：
 * 400 参数校验失败，404 资源不存在，409 状态冲突/同键异参/罐被占用，422 组分不足等业务拒绝。
 */
public class BlendException extends RuntimeException {

    private final int status;

    public BlendException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static BlendException notFound(String message) {
        return new BlendException(404, message);
    }

    public static BlendException conflict(String message) {
        return new BlendException(409, message);
    }

    public static BlendException unprocessable(String message) {
        return new BlendException(422, message);
    }

    /**
     * 统一错误响应处理。
     */
    @RestControllerAdvice
    public static class Handler {

        @ExceptionHandler(BlendException.class)
        public ResponseEntity<ErrorBody> handleBlend(BlendException ex) {
            HttpStatus status = HttpStatus.valueOf(ex.status());
            return ResponseEntity.status(status)
                    .body(new ErrorBody(status.getReasonPhrase(), ex.getMessage()));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
            String message = ex.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + " " + e.getDefaultMessage())
                    .findFirst()
                    .orElse("请求参数校验失败");
            return ResponseEntity.badRequest()
                    .body(new ErrorBody(HttpStatus.BAD_REQUEST.getReasonPhrase(), message));
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
            return ResponseEntity.badRequest()
                    .body(new ErrorBody(HttpStatus.BAD_REQUEST.getReasonPhrase(), "请求报文无法解析"));
        }
    }
}
