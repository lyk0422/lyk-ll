package com.example.starter.domain;

import java.time.Instant;

/**
 * 新增写操作幂等台账：同键同参重放首次冻结结果，异参 409，失败不占键。
 *
 * @param requestId      客户端幂等键，全局唯一（主键）
 * @param fingerprint    结构化幂等指纹：操作类型与全部业务参数（含版本、集合、精确时刻）长度前缀编码的 SHA-256
 * @param operation      操作类型：SEAL_REGISTER / UNSEAL_APPROVE / UNSEAL_REVOKE / RESEAL / CONTROLLED_ADJUST
 * @param resultSnapshot 首次成功结果的冻结快照（JSON），同键同参重放时原样返回
 * @param createdAt      首次受理时间（UTC）
 */
public record IdempotencyRecord(String requestId, String fingerprint, String operation,
                                String resultSnapshot, Instant createdAt) {
}
