package com.example.starter.web;

/**
 * 业务拒绝（HTTP 422）：请求合法但违反业务规则，如浓度越界、超容量、
 * 实际量超预占、单人交接、重复运输批号、计划状态不允许等。
 */
public class BusinessRejectedException extends RuntimeException {

    public BusinessRejectedException(String message) {
        super(message);
    }
}
