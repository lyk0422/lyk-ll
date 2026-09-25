package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与拒绝分类，由全局异常处理统一转换。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final RejectCategory category;

    public BusinessException(HttpStatus httpStatus, RejectCategory category, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.category = category;
    }

    public static BusinessException conflict(RejectCategory category, String message) {
        return new BusinessException(HttpStatus.CONFLICT, category, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, RejectCategory.ILLEGAL_STATE, message);
    }

    public static BusinessException unprocessable(RejectCategory category, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, category, message);
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public RejectCategory getCategory() {
        return category;
    }
}
