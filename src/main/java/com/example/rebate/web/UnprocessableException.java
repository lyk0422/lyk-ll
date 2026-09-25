package com.example.rebate.web;

/**
 * 422 业务校验失败（如疑点未解除即申报、终态后操作非法）。
 */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String message) {
        super(422, message);
    }
}
