package com.example.starter.spectrum.service;

import org.springframework.http.HttpStatus;

/**
 * 请求非法（参数区间不合法、区域集合为空等），映射 400。
 */
public class InvalidLeaseRequestException extends LeaseServiceException {

    public InvalidLeaseRequestException(String message) {
        super("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message);
    }
}
