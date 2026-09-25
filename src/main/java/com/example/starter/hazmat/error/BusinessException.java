package com.example.starter.hazmat.error;

/**
 * 业务校验失败（HTTP 422）：类别路线不匹配、管制时段禁行、车辆冻结、打卡逆序等。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
