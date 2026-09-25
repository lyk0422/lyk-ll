package com.example.starter.museum.domain.po;

import com.example.starter.museum.domain.WorkOrderStatus;

import java.time.Instant;

/**
 * 保护工单行。异常触发时自动生成，deadline=triggerTime+24h（UTC）。
 * deadline 恰满（>=）仍未确认则升级 URGENT 并冻结展柜；确认后解除冻结。
 */
public class ProtectionWorkOrderPo {
    public Long id;
    public String orderNo;
    public Long alertId;
    public Long showcaseId;
    public WorkOrderStatus status;
    public Instant deadline;
    public Instant confirmedAt;
    public String handler;
    public String note;
    public Instant createdAt;
}
