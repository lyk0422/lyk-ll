package com.example.starter.cems;

/**
 * 业务拒绝（语义不合法，非参数格式错误也非并发冲突）：HTTP 422。
 * 用于：缺雨量待核时要求合规、冻结后改雨量、段超长专项异常等。
 */
public class BusinessRejectException extends RuntimeException {

    public BusinessRejectException(String message) {
        super(message);
    }
}
