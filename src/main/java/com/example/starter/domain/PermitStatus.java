package com.example.starter.domain;

/**
 * 爆破作业申请单状态。
 * PENDING 在途；APPROVED 已批准；REJECTED 已拒绝；VOID 冻结作废；
 * FILED 已起爆备案；SEALED 评估封档（终态）。
 */
public enum PermitStatus {
    PENDING,
    APPROVED,
    REJECTED,
    VOID,
    FILED,
    SEALED
}
