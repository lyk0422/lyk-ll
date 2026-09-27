package com.example.starter.runway.dto;

/**
 * 例行巡检检查回报响应。
 *
 * @param reportId   回报记录主键
 * @param planId     所属计划ID
 * @param late       是否迟到登记（计划已被抢占，仅登记不开放跑道）
 * @param planStatus 回报后的计划状态
 * @param version    回报后的计划乐观锁版本（迟到登记不改变计划，版本不变）
 */
public record CheckReportResponse(
        long reportId,
        long planId,
        boolean late,
        String planStatus,
        int version) {
}
