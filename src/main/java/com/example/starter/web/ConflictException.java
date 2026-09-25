package com.example.starter.web;

/**
 * 409 冲突：幂等键异参、同站已有活动调度单、油机已被占用等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(409, code, message);
    }
}
