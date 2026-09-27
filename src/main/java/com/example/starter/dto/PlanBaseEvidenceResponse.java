package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 指令计划基数证据：受理计划绑定指令时冻结的计划版本与各机组基数，
 * 后续发布新计划不改变本证据；重复查询结果稳定。
 */
public record PlanBaseEvidenceResponse(
        Long orderId,
        String orderNo,
        Long planId,
        int planVersion,
        LocalDate planDate,
        int periodIndex,
        Instant frozenAt,
        List<BaseItem> items) {

    /**
     * 单机组冻结基数。
     *
     * @param turbineCode  机组编码
     * @param plannedKw    受理时冻结的该段核定计划出力（kW）
     * @param availableKw  受理时机组实时可用出力（kW）
     * @param baseKw       可参与出力 = min(availableKw, plannedKw)（kW）
     */
    public record BaseItem(
            String turbineCode,
            int plannedKw,
            int availableKw,
            int baseKw) {
    }
}
