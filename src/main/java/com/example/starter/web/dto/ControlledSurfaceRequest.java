package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 受控潜次出水请求：原子更新两人积分账与本潜次记录。
 *
 * @param requestId       本次操作幂等键
 * @param diveRequestId   所属受控潜次 requestId
 * @param expectedVersion 期望当前方案版本；与冻结版本不一致则拒绝
 * @param bottomMin       本次整数水下分钟数（正整数实际底时间），用于计算出水残留积分
 * @param surfacedAt      已确认出水时刻（UTC）；为空取服务时钟
 */
public record ControlledSurfaceRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @Positive int bottomMin,
        Instant surfacedAt) {
}
