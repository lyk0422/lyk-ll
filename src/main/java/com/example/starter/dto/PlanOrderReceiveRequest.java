package com.example.starter.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 计划绑定模式限电指令接收请求。
 * 须指定日期、时段与已发布版本；受理时冻结机组基数及版本，
 * 可参与出力为各机组实时可用出力与该段核定计划的较小者。
 *
 * @param requestId    幂等键，覆盖本对象全部业务字段（含版本与精确时刻）
 * @param farmCode     风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 * @param planDate     计划 UTC 日期
 * @param slotIndex    时段序号 0-95
 * @param planVersion  已发布计划版本号（必须为当前版本，过期版本拒绝绑定）
 */
public record PlanOrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        @NotNull LocalDate planDate,
        @NotNull @Min(0) @Max(95) Integer slotIndex,
        @NotNull @Min(1) Integer planVersion) {
}
