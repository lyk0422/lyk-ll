package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 日评估相关请求/响应。评估以 UTC 日界划分，封存（SEALED）为终态；重复封存幂等返回首次结果，
 * 迟到读数只记录迟到事件不参与评估。
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    public record EvaluationRequest(
            @NotBlank @Size(max = 64) String zoneCode,
            @NotNull LocalDate evalDate) {
    }

    /** 按指令类型汇总的能耗明细，单位 kWh。 */
    public record EnergyItem(String commandType, int commandCount, BigDecimal energyKwh) {
    }

    public record EvaluationResponse(
            Long id,
            String zoneCode,
            LocalDate evalDate,
            String status,
            int inBandCount,
            int totalCount,
            BigDecimal achievementRate,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh,
            List<EnergyItem> energyDetail,
            Instant sealedAt,
            boolean replayed) {
    }
}
