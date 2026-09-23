package com.example.starter.domain;

/**
 * 已冻结的交接结果。
 *
 * @param id              自增主键
 * @param shipmentId      所属货件
 * @param requestId       交接请求幂等键（货件内唯一）
 * @param fingerprint     请求参数指纹（SHA-256），同键异参冲突
 * @param custodian       交出方（须与关闭段保管方一致）
 * @param expectedVersion 请求方预期的当前版本（即被关闭段 version）
 * @param receiver        接收方（新段保管方）
 * @param handoverAt      交接时间，Unix 毫秒时间戳（UTC）
 * @param lastSequence    本次交接覆盖的最后一个设备序号
 * @param resultStatus    首次冻结的判定结果
 * @param closedVersion   被关闭段的 version
 * @param createdAt       首次处理时间，Unix 毫秒时间戳（UTC）
 */
public record HandoverRecord(
        Long id,
        String shipmentId,
        String requestId,
        String fingerprint,
        String custodian,
        int expectedVersion,
        String receiver,
        long handoverAt,
        long lastSequence,
        SegmentStatus resultStatus,
        int closedVersion,
        long createdAt
) {
}
