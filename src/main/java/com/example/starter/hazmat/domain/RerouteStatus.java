package com.example.starter.hazmat.domain;

/**
 * 改线申请状态：PENDING 待核准（原路线打卡继续有效）；APPROVED 已核准并原子切换；
 * REJECTED 已拒绝。
 */
public enum RerouteStatus {
    PENDING,
    APPROVED,
    REJECTED
}
