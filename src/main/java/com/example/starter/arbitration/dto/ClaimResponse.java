package com.example.starter.arbitration.dto;

/**
 * 租约申请/续期结果。
 *
 * @param claimId        租约ID
 * @param state          租约状态：ACTIVE/RELEASED/PREEMPTED/EXPIRED/ESTOPPED
 * @param leaseExpiresAt 租约到期时刻（epoch毫秒）
 * @param replayed       是否为幂等重放命中（同 requestId 同参数）
 */
public record ClaimResponse(long claimId, String state, long leaseExpiresAt, boolean replayed) {
}
