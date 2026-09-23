package com.example.starter.trajectory.service;

/**
 * 指定的规则版本不存在。
 */
public class RuleVersionNotFoundException extends RuntimeException {

    public RuleVersionNotFoundException(String ruleVersion) {
        super("rule version not found: " + ruleVersion);
    }
}
