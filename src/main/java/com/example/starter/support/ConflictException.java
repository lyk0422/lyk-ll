package com.example.starter.support;

/**
 * 业务冲突（HTTP 409）：幂等同键异参、同线路同日期重复评估、车辆已被执行中线路占用等。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
