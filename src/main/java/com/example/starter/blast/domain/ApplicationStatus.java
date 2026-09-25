package com.example.starter.blast.domain;

/**
 * 爆破作业申请生命周期状态。
 * SUBMITTED：在途申请（已申请待审批）
 * APPROVED：审批通过、尚未起爆备案
 * REJECTED：审批驳回（含冻结时在途申请被一并驳回）
 * FILED：已完成起爆备案，等待振动回传与效果评估
 * VOIDED：已批准未起爆作业因矿区冻结被作废
 * ARCHIVED：效果评估封档，终态
 */
public enum ApplicationStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    FILED,
    VOIDED,
    ARCHIVED
}
