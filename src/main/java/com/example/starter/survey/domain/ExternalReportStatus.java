package com.example.starter.survey.domain;

/**
 * 外部报告修订状态。
 */
public enum ExternalReportStatus {
    /** 当前最新修订。 */
    CURRENT,
    /** 已被更新修订替代（历史行不可变）。 */
    SUPERSEDED
}
