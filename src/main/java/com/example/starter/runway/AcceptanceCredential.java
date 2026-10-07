package com.example.starter.runway;

import java.time.Instant;

/**
 * 验收清场凭据行（合成）：对应 acceptance_credential 表。
 *
 * @param id          凭据主键
 * @param segmentId   所属施工段ID
 * @param orderId     所属施工单ID
 * @param inspectorId 验收人标识（合成）；同段同一验收人仅计一次
 * @param credential  合成清场凭据文本
 * @param submittedAt 凭据提交时刻（UTC）
 * @param late        是否迟到凭据：true=施工单已结束后提交，仅登记留痕
 * @param requestId   凭据提交幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 */
public record AcceptanceCredential(
        long id,
        long segmentId,
        long orderId,
        String inspectorId,
        String credential,
        Instant submittedAt,
        boolean late,
        String requestId,
        Instant createdAt) {
}
