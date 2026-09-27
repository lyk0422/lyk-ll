package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发电车组合调度/改派结果。
 *
 * @param orderId         调度单ID（改派时为新单ID）
 * @param workOrderId     工单ID
 * @param version         当前版本，从1开始
 * @param status          调度单状态
 * @param totalLoadKw     合计负荷，单位kW
 * @param totalCapacityKw 组合总容量，单位kW
 * @param vehicleIds      本单全部发电车ID（含改派结转车）
 * @param dispatchedAt    UTC下发时刻
 * @param deadlineAt      UTC到场截止时刻（下发+30分钟）
 * @param replayed        是否为同键同参重放
 */
public record GeneratorDispatchResponse(
        Long orderId,
        Long workOrderId,
        int version,
        String status,
        int totalLoadKw,
        int totalCapacityKw,
        List<Long> vehicleIds,
        LocalDateTime dispatchedAt,
        LocalDateTime deadlineAt,
        boolean replayed) {
}
