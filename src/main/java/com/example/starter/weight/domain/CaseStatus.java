package com.example.starter.weight.domain;

/**
 * 案件状态。
 */
public enum CaseStatus {
    /**
     * 案件处理中（含复检期间），处罚不停止执行。
     */
    OPEN,
    /**
     * 结案（终态）：迟到过磅数据仅入证据备查，不改判。
     */
    CLOSED
}
