package com.example.starter.web;

/**
 * 业务拒绝（422）：违反业务规则但参数本身合法，如安全储备不足、重复运输批号、
 * 单人交接、超容量入账、计划已终结等。
 */
public class BusinessRejectedException extends RuntimeException {

    public BusinessRejectedException(String message) {
        super(message);
    }
}
