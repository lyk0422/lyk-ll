package com.example.starter.epidemic.service;

import org.springframework.http.HttpStatus;

/**
 * 业务规则冲突（422）：状态不允许该操作、迟报缺原因、归档后订正被拒、审核员相同等。
 */
public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
