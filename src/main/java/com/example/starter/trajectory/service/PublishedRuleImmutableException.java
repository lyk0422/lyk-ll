package com.example.starter.trajectory.service;

/**
 * 已发布的规则配置不可原地修改。
 */
public class PublishedRuleImmutableException extends RuntimeException {

    public PublishedRuleImmutableException(String ruleVersion) {
        super("published rule config is immutable: " + ruleVersion);
    }
}
