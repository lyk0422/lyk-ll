package com.example.starter.trajectory.domain;

/**
 * 规则配置生命周期状态。
 * DRAFT：草稿，可修改；PUBLISHED：已发布，不可原地修改，只能新建版本。
 */
public enum RuleStatus {
    DRAFT,
    PUBLISHED
}
