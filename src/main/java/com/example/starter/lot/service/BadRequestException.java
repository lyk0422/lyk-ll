package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 请求参数或业务规则不满足（400）：精度非法、数量非正、单位不一致、
 * 损耗越界、形成谱系环等，整笔转换必须回滚。
 */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
