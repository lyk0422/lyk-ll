package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 市电恢复结果。
 *
 * @param requestId      幂等键
 * @param stationCode    基站编号
 * @param eventId        停电事件 ID
 * @param restoredAt     恢复时刻，UTC
 * @param releasedOrder  被解除的调度单视图；无活动单时为 null
 * @param batteryFullWh  回充后电池容量，单位 Wh
 */
public record RestorationResponse(
        String requestId,
        String stationCode,
        long eventId,
        Instant restoredAt,
        OrderResponse releasedOrder,
        java.math.BigDecimal batteryFullWh
) {
}
