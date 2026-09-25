package com.example.starter.web;

/**
 * 422：请求格式正确但当前状态不允许该操作（如未到超时时刻即改派）。
 */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String code, String message) {
        super(422, code, message);
    }
}
