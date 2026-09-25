package com.example.starter.web;

/**
 * 404：基站、油机、调度单等资源不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(404, code, message);
    }
}
