package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 反复潜水余量积分受控方案请求（虚构模型，仅供软件验证）。
 *
 * @param requestId       本次受控方案操作的幂等键
 * @param diveRequestId   所属潜次 requestId
 * @param depthM          方案深度，米
 * @param bottomMin       方案底时间，分钟（须不超过全队较短允许时长，恰用尽合法）
 * @param expectedVersion 期望的潜次当前方案版本：首次出方案传 0，每次成功变更后加 1
 * @param planAt          方案时刻（UTC，精确时刻）；积分消减与间隔门禁以此刻计算，为空取服务时钟
 */
public record ControlledPlanRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @Positive int depthM,
        @Positive int bottomMin,
        @PositiveOrZero int expectedVersion,
        Instant planAt) {
}
