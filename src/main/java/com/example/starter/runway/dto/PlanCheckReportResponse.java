package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 检查项回报结果：计划中止后的迟到回报 recordedLate=true 且 planStatus=ABORTED，不开放跑道。
 *
 * @param planId              所属计划ID
 * @param itemCode            检查项编码
 * @param recordedLate        是否中止后迟到登记
 * @param planStatus          回报后计划状态
 * @param planVersion         回报后计划版本号（每次成功变更加1；迟到登记不改变计划版本）
 * @param completedItemCount  已完成（含迟到登记）检查项数
 * @param totalItemCount      检查项总数
 * @param planCompleted       本次回报后计划是否正常完成（迟到回报恒为 false）
 * @param completedAt         检查项完成时刻（UTC）
 */
public record PlanCheckReportResponse(
        long planId,
        String itemCode,
        boolean recordedLate,
        String planStatus,
        int planVersion,
        int completedItemCount,
        int totalItemCount,
        boolean planCompleted,
        Instant completedAt) {
}
