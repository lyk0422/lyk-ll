package com.example.starter.marpol.error;

import org.springframework.http.HttpStatus;

/**
 * 422：业务规则不满足，如特殊区域内排污、垃圾距陆地不足 12 海里、航次已关闭等。
 */
public class RuleViolationException extends ApiException {

    public RuleViolationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
