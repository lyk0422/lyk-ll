package com.example.starter.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 学生上下车打卡请求。
 *
 * @param requestId 幂等请求 ID
 * @param routeId   线路 ID
 * @param studentId 学生 ID
 * @param checkDate 打卡日期（UTC）；为空取当前 UTC 日期
 */
public record CheckInRequest(
        @NotBlank String requestId,
        @NotNull Long routeId,
        @NotNull Long studentId,
        LocalDate checkDate) {

    /**
     * 打卡结果。
     *
     * @param checkInId 打卡记录 ID（去重时为首次记录 ID）
     * @param routeId   线路 ID
     * @param studentId 学生 ID
     * @param checkDate 打卡日期（UTC）
     * @param deduped   是否命中学生+日期去重（true 表示返回既有记录）
     */
    public record Result(Long checkInId, Long routeId, Long studentId, LocalDate checkDate, boolean deduped) {
    }
}
