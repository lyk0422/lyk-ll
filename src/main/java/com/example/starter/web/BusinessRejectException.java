package com.example.starter.web;

/**
 * 业务规则拒绝（HTTP 422）：如安全储备拦截加注、验收浓度越界、补液超容量、
 * 运输批号重复、交接人同一人、对终态计划重复回执/取消等。
 */
public class BusinessRejectException extends RuntimeException {

    public BusinessRejectException(String message) {
        super(message);
    }
}
