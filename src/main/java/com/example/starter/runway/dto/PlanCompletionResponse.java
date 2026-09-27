package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行巡检完成回报响应。
 *
 * @param planId  计划ID
 * @param status  回报后计划状态（正常完成=COMPLETED；被抢占后迟到回报保持 ABORTED/CANCELLED）
 * @param version 回报后计划聚合版本（迟到回报不改变计划聚合，返回其当前版本）
 * @param late    是否迟到完成：true=计划已被紧急取消/中止后回报，仅登记不开放跑道
 */
public record PlanCompletionResponse(
        long planId,
        String status,
        int version,
        boolean late,
        Instant completedAt) {
}
