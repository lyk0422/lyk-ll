package com.example.starter.hazmat.model;

import com.example.starter.hazmat.domain.WaybillStatus;
import java.time.LocalDateTime;

/**
 * 运输运单。
 *
 * @param id               运单主键
 * @param requestId        幂等请求标识
 * @param fingerprint      幂等指纹，覆盖 requestId 与全部业务字段
 * @param vehicleCode      车辆编码
 * @param hazmatLevel      危化品类别等级
 * @param routeId          当前生效路线主键
 * @param status           运单状态
 * @param plannedDeparture 计划发运时刻
 * @param routeGeneration 当前路线代次：发运为 1，每次改线生效 +1
 * @param closedAt         结案/失联终态时刻，非终态为 null
 * @param createdAt        创建时刻
 * @param updatedAt        最近更新时刻
 */
public record Waybill(
        long id,
        String requestId,
        String fingerprint,
        String vehicleCode,
        int hazmatLevel,
        long routeId,
        WaybillStatus status,
        LocalDateTime plannedDeparture,
        int routeGeneration,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
