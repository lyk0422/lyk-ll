package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 指令基数证据：绑定模式指令受理时冻结的计划版本与各机组基数。
 * bound=false 表示该指令未绑定日前计划（此时其余字段为空）。
 */
public record PlanBindingEvidenceResponse(
        String orderNo,
        boolean bound,
        Long planId,
        Integer planVersion,
        LocalDate planDate,
        Integer slotIndex,
        Instant slotStartUtc,
        Instant slotEndUtc,
        List<TurbineBaseView> bases) {

    /** 单机组冻结基数：核定计划、受理时实时可用出力及二者较小者（可参与出力）。 */
    public record TurbineBaseView(
            String turbineCode,
            int plannedKw,
            int availableKw,
            int effectiveKw) {
    }
}
