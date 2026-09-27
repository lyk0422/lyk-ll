package com.example.starter.cosmetic.service;

import java.util.List;

/**
 * 业务拒绝异常，映射为 HTTP 422：当前状态不允许的操作、
 * 儿童备案分龄审查缺项零生效等业务规则拒绝。
 *
 * <p>区别于 409（幂等同键异参、版本冲突等并发/幂等冲突）。</p>
 */
public class UnprocessableException extends RuntimeException {

    /** 业务错误码（机器可读）。 */
    private final String code;

    /** 缺项/拒绝明细（一次列全）；无明细时为空列表。 */
    private final List<String> violations;

    public UnprocessableException(String code, String message) {
        this(code, message, List.of());
    }

    public UnprocessableException(String code, String message, List<String> violations) {
        super(message);
        this.code = code;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public String code() {
        return code;
    }

    public List<String> violations() {
        return violations;
    }
}
