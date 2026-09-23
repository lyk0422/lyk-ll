package com.example.starter.signing.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.service.SigningService;

/**
 * 统一错误响应：业务异常返回对应 HTTP 状态码与稳定错误码；参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务规则拒绝（未开放步骤、重复、过期、取消、摘要不符、幂等冲突等）。
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<SigningService.ErrorBody> handleBusiness(BusinessRuleException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new SigningService.ErrorBody(ex.getCode(), ex.getMessage()));
    }

    /**
     * Bean Validation 参数校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SigningService.ErrorBody> handleValidation(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest()
                .body(new SigningService.ErrorBody("INVALID_REQUEST", message));
    }

    /**
     * 其余未预期错误返回 500，不泄漏内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<SigningService.ErrorBody> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SigningService.ErrorBody("INTERNAL_ERROR", "服务内部错误"));
    }
}
