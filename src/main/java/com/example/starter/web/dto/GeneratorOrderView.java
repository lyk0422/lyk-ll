package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发电车调度单详情。
 *
 * @param orderId         调度单ID
 * @param workOrderId     工单ID
 * @param status          调度单状态
 * @param version         乐观版本
 * @param totalLoadKw     合计负荷，单位kW
 * @param totalCapacityKw 组合总容量，单位kW
 * @param dispatchedAt    UTC下发时刻
 * @param deadlineAt      UTC到场截止时刻
 * @param allArrivedAt    全部到场时刻（UTC），空表示未全到场
 * @param suppliedAt      临时供电时刻（UTC），空表示未供电
 * @param replacedById    改派后新单ID，空表示未被改派
 * @param cancelReason    撤销原因，空表示未撤销
 * @param vehicles        组合车辆到场视图
 * @param events          调度单事件轨迹（UTC）
 */
public record GeneratorOrderView(
        Long orderId,
        Long workOrderId,
        String status,
        int version,
        int totalLoadKw,
        int totalCapacityKw,
        LocalDateTime dispatchedAt,
        LocalDateTime deadlineAt,
        LocalDateTime allArrivedAt,
        LocalDateTime suppliedAt,
        Long replacedById,
        String cancelReason,
        List<OrderVehicleView> vehicles,
        List<String> events) {
}
