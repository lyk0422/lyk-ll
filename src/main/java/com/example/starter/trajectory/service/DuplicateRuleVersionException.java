package com.example.starter.trajectory.service;

/**
 * 规则版本号已存在，不能重复创建。
 */
public class DuplicateRuleVersionException extends RuntimeException {

    public DuplicateRuleVersionException(String ruleVersion) {
        super("rule version already exists: " + ruleVersion);
    }
}
