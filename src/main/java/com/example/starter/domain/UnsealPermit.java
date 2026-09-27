package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 临时解封单：引用铅封号与阀门版本，两名不同授权人确认，
 * 固定 UTC 半开窗口 [windowStart, windowEnd) 与最多两次调节额度。
 *
 * @param id           主键
 * @param sealId       引用的铅封台账 ID
 * @param valveId      受控阀门 ID（冗余自台账，便于按阀门串行化）
 * @param valveVersion 最近核对的阀门开度版本：批准时为申请值，每次消费额度后同步为最新版本
 * @param approvers    两名不同授权人（按字典序排序存储）
 * @param windowStart  解封有效窗口起点（UTC，含）
 * @param windowEnd    解封有效窗口终点（UTC，不含；恰到终点失效）
 * @param quotaTotal   调节额度上限，固定 2
 * @param quotaUsed    已消费调节额度，失败不消费
 * @param status       状态：ACTIVE 生效中 / REVOKED 已撤销 / CLOSED 已随复封终结
 * @param version      解封单自身版本，从 1 开始，每次成功变更 +1
 * @param revokedAt    撤销时刻（UTC）；未撤销为 null
 * @param closedAt     随复封终结时刻（UTC）；未终结为 null
 * @param createdAt    批准时间（UTC）
 */
public record UnsealPermit(long id, long sealId, long valveId, int valveVersion,
                           List<String> approvers, Instant windowStart, Instant windowEnd,
                           int quotaTotal, int quotaUsed, PermitStatus status, int version,
                           Instant revokedAt, Instant closedAt, Instant createdAt) {

    /** 调节额度上限：每张解封单最多两次调节。 */
    public static final int MAX_QUOTA = 2;

    /**
     * 解封单状态机：ACTIVE → REVOKED（撤销终态）/ CLOSED（复封终结终态）。
     */
    public enum PermitStatus {
        /** 生效中：窗口内可消费额度调节。 */
        ACTIVE,
        /** 已撤销：人工撤销，禁止新调节。 */
        REVOKED,
        /** 已终结：随复封原子终结，禁止新调节。 */
        CLOSED
    }
}
