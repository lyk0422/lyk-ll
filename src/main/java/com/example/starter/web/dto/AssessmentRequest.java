package com.example.starter.web.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 线路安全评估请求。
 *
 * @param requestId      幂等请求 ID
 * @param routeId        线路 ID
 * @param vehicleId      候选车辆 ID
 * @param roadGrade      道路等级
 * @param stopDistancesM 相邻站点间距（米），按站点顺序排列
 */
public record AssessmentRequest(
        @NotBlank String requestId,
        @NotNull Long routeId,
        @NotNull Long vehicleId,
        @NotNull Integer roadGrade,
        @NotEmpty List<@NotNull Integer> stopDistancesM) {

    /**
     * 评估结果。
     *
     * @param assessmentId 评估记录 ID
     * @param routeId      线路 ID
     * @param assessDate   评估日期（UTC）
     * @param passed       是否通过
     * @param reasons      拒绝原因列表；通过时为空
     * @param validFrom    资格生效日（通过时非空）
     * @param validTo      资格到期日（通过时非空），恰满当日 24:00 UTC 失效
     */
    public record Result(Long assessmentId, Long routeId, LocalDate assessDate, boolean passed,
                         List<String> reasons, LocalDate validFrom, LocalDate validTo) {
    }
}
