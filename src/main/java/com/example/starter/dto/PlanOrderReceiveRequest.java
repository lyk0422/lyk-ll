package com.example.starter.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 计划绑定模式限电指令接收请求。
 * 须指定风场、UTC 日期、时段和已发布（当前）版本；可参与出力为各机组实时可用出力
 * 与该段核定计划的较小者，据此套用最低保留与比例分摊；目标不可实现整单拒绝。
 *
 * @param requestId       幂等键，覆盖全部业务字段（含精确时刻）
 * @param farmCode        风场编码
 * @param targetPowerKw   限电后全场目标出力（kW，正整数）
 * @param planDate        绑定计划 UTC 日期 yyyy-MM-dd
 * @param segmentIndex    绑定的 15 分钟半开时段序号 0-95（UTC）
 * @param expectedVersion 期望绑定的已发布版本号（必须为该风场该日期当前版本）
 * @param opTime          请求精确时刻（UTC，ISO-8601），作为接收时刻，参与指纹
 */
public record PlanOrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        @NotBlank String planDate,
        @NotNull @PositiveOrZero Integer segmentIndex,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant opTime) {
}
