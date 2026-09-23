package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 业务规则异常（422）：单位不一致、数量越界、损耗越界、谱系环等。
 * 与 409 的区别：422 表示请求本身不满足业务规则，无论并发与否都不会成功。
 */
public class BusinessRuleException extends BusinessException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
