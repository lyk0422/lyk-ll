package com.example.starter.domain;

import java.time.Instant;

/**
 * 临时解封单：批准窗口与调节额度内的受控调节授权。
 *
 * @param id                主键
 * @param sealId            引用的铅封台账 ID
 * @param valveId           受控阀门 ID
 * @param sealNo            引用的铅封号
 * @param valveVersion      批准时引用的阀门开度版本；受控调节要求当前版本 = 本版本 + 已消费额度
 * @param approverA         授权人甲，须与授权人乙不同
 * @param approverB         授权人乙，须与授权人甲不同
 * @param windowStart       有效窗口起点（UTC，半开区间，含）
 * @param windowEnd         有效窗口终点（UTC，半开区间，不含；恰到终点失效）
 * @param quotaTotal        调节额度上限，固定为 2
 * @param quotaUsed         已消费额度
 * @param status            状态：ACTIVE 有效 / REVOKED 已撤销 / CLOSED 复封终结
 * @param version           乐观锁版本，从 1 开始，成功变更（含消费额度）加 1
 * @param createdAt         解封批准时刻（UTC），生效不改变开度
 * @param revokedAt         撤销时刻（UTC）；未撤销时为 null
 * @param requestId         客户端幂等键（批准请求），全局唯一
 * @param fingerprint       幂等指纹：requestId 与全部业务字段的结构化 SHA-256
 * @param revokeRequestId   撤销请求的幂等键；未撤销时为 null
 * @param revokeFingerprint 撤销请求的幂等指纹；未撤销时为 null
 */
public record UnsealOrder(long id, long sealId, long valveId, String sealNo, int valveVersion,
                          String approverA, String approverB,
                          Instant windowStart, Instant windowEnd,
                          int quotaTotal, int quotaUsed, UnsealStatus status, int version,
                          Instant createdAt, Instant revokedAt,
                          String requestId, String fingerprint,
                          String revokeRequestId, String revokeFingerprint) {

    /**
     * 解封单状态机：ACTIVE → REVOKED / CLOSED（均为终态）。
     */
    public enum UnsealStatus {
        /** 有效：窗口内且额度足够时允许受控调节。 */
        ACTIVE,
        /** 已撤销：禁止新的受控调节，不回拨已改开度。 */
        REVOKED,
        /** 复封终结：复封时原子关闭，禁止新的受控调节。 */
        CLOSED
    }
}
