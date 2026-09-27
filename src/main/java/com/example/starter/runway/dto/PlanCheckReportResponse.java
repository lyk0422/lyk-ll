package com.example.starter.runway.dto;

import java.util.List;

/**
 * 例行检查项回报响应。
 *
 * @param planId      计划ID
 * @param status      回报后计划状态
 * @param version     回报后聚合版本（迟到登记不改变计划聚合，返回其当前版本）
 * @param late        是否迟到回报：true=计划已被紧急取消/中止，仅登记，不开放跑道
 * @param savedItems  实际保存的检查项视图
 */
public record PlanCheckReportResponse(
        long planId,
        String status,
        int version,
        boolean late,
        List<SavedCheckItem> savedItems) {

    /**
     * 已保存检查项视图。
     */
    public record SavedCheckItem(long itemId, String itemCode, String itemText, boolean passed,
                                 boolean late) {
    }
}
