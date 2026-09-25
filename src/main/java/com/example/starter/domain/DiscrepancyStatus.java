package com.example.starter.domain;

/**
 * 长短款挂账状态：OPEN 挂账中；SETTLED 已在 3 个自然日内销账；ESCALATED 逾期升级并冻结责任人新任务。
 */
public enum DiscrepancyStatus {
    OPEN,
    SETTLED,
    ESCALATED
}
