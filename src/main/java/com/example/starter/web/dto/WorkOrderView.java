package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情视图，含状态轨迹、复电确认链与发电车事件。
 *
 * @param id                工单ID
 * @param feederId          馈线编号
 * @param faultPoint        故障点
 * @param affectedUsers     影响用户数
 * @param importantLevel    重要用户等级
 * @param status            工单状态
 * @param teamId            班组ID，空表示未指派
 * @param outageStartAt     停电开始时间
 * @param restoredAt        复电时间，空表示未复电
 * @param mergedIntoId      合并目标工单ID，空表示未被合并
 * @param generatorStartedAt 发电车流程启动时间，空表示未启动
 * @param events            状态轨迹事件（按时间升序）
 */
public record WorkOrderView(
        long id,
        String feederId,
        String faultPoint,
        int affectedUsers,
        String importantLevel,
        String status,
        Long teamId,
        LocalDateTime outageStartAt,
        LocalDateTime restoredAt,
        Long mergedIntoId,
        LocalDateTime generatorStartedAt,
        List<String> events) {
}
