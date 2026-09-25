package com.example.starter.rolling.service;

import java.util.List;

/**
 * 业务规则违例：携带全部违例明细，由接口层整单拒绝（HTTP 422）。
 */
public class ValidationException extends RuntimeException {

    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super("排产单校验违例，整单拒绝");
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
