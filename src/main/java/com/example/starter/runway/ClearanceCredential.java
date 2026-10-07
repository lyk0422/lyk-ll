package com.example.starter.runway;

import java.time.Instant;

/**
 * 清场凭据（合成）：同一施工段两个不同验收人各一份后段完成；迟到凭据只读留痕。
 *
 * @param id            凭据主键
 * @param segmentId     所属施工段ID
 * @param orderId       所属施工单ID（冗余便于按单查询留痕）
 * @param acceptor      验收人标识（合成）
 * @param credentialRef 清场凭据编号（合成）
 * @param submittedAt   凭据提交时刻（UTC，由请求给出）
 * @param late          是否迟到凭据：true=段已完成或单已结束后提交，只读留痕
 * @param requestId     凭据幂等请求标识
 * @param createdAt     记录创建时间（UTC）
 */
public record ClearanceCredential(
        long id,
        long segmentId,
        long orderId,
        String acceptor,
        String credentialRef,
        Instant submittedAt,
        boolean late,
        String requestId,
        Instant createdAt) {
}
