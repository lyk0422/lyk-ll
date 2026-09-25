package com.example.starter.atm.service;

import com.example.starter.atm.web.ApiException;
import org.springframework.http.HttpStatus;

/** 幂等重放命中的历史失败响应：按首次响应的状态码与消息原样抛出。 */
public class ReplayedApiException extends ApiException {
    public ReplayedApiException(int statusCode, String message) {
        super(HttpStatus.valueOf(statusCode), message);
    }
}
