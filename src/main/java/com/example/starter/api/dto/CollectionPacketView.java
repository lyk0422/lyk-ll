package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单个采集包的证据视图（原始包证据查询）。
 *
 * @param state          ADMITTED=已入前缀，BUFFERED=乱序缓冲，LATE=归档后迟到
 * @param attemptNo      入账后对应紧固点力矩测量序号；缓冲/迟到为 null
 * @param inTolerance    入账后是否在允差内；缓冲/迟到为 null
 * @param reworkNo       入账触发的返工序号（1/2），合格或缓冲/迟到为 null
 * @param measuredAt     设备 UTC 测量时刻
 * @param receivedAt     服务端接收时间(UTC)
 * @param admittedAt     前缀入账时间(UTC)，缓冲/迟到为 null
 * @param requestId      首次提交该包的幂等请求号
 */
public record CollectionPacketView(
        int seq,
        String pointCode,
        BigDecimal measuredTorque,
        Instant measuredAt,
        String state,
        Integer attemptNo,
        Boolean inTolerance,
        Integer reworkNo,
        String requestId,
        Instant receivedAt,
        Instant admittedAt) {
}
