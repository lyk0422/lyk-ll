package com.example.starter.domain;

import java.time.Instant;

/**
 * 临时解封单：两名不同授权人共同确认，固定 UTC 半开窗口与最多两次调节额度。
 *
 * @param id                   主键
 * @param valveId              阀门 ID
 * @param sealNo               引用的铅封号（批准时阀门的当前铅封）
 * @param expectedValveVersion 期望的下一开度版本号：批准时等于当前版本，每次消费随开度版本同步加 1
 * @param authorizerA          授权人甲
 * @param authorizerB          授权人乙，须与甲不同
 * @param windowStart          有效窗口起点（UTC），半开区间含起点
 * @param windowEnd            有效窗口终点（UTC），半开区间不含终点，恰到终点失效
 * @param quotaTotal           调节额度上限，最多 2 次
 * @param quotaUsed            已消费额度，仅在受控调节成功事务内加 1，失败不消费
 * @param status               状态：ACTIVE 生效中 / REVOKED 已撤销 / TERMINATED 复封终结
 * @param requestId            批准操作的客户端幂等键，全局唯一
 * @param fingerprint          批准操作的幂等指纹：requestId 与全部业务字段的结构化 SHA-256
 * @param revokeRequestId      撤销操作的客户端幂等键；未撤销时为 null
 * @param revokeFingerprint    撤销操作的幂等指纹；未撤销时为 null
 * @param version              解封单行版本，从 1 开始，成功变更（消费/撤销/终结）加 1
 * @param createdAt            批准时间（UTC）
 * @param updatedAt            最后变更时间（UTC）
 */
public record UnsealOrder(long id, long valveId, String sealNo, int expectedValveVersion,
                          String authorizerA, String authorizerB,
                          Instant windowStart, Instant windowEnd,
                          int quotaTotal, int quotaUsed, OrderStatus status,
                          String requestId, String fingerprint,
                          String revokeRequestId, String revokeFingerprint,
                          int version, Instant createdAt, Instant updatedAt) {

    /**
     * 解封单状态机：ACTIVE → REVOKED（撤销）或 TERMINATED（复封终结）。
     */
    public enum OrderStatus {
        /** 生效中：窗口内且额度足够时允许受控调节。 */
        ACTIVE,
        /** 已撤销：不再允许新的受控调节。 */
        REVOKED,
        /** 复封终结：复封时原子终结，不再允许新的受控调节。 */
        TERMINATED
    }
}
