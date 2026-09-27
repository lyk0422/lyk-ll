package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 受控出水登记请求：原子更新两人积分账与本潜次积分流水，并释放潜水员占用槽。
 *
 * @param requestId       本次出水操作的幂等键
 * @param diveRequestId   所属受控潜次 requestId
 * @param expectedVersion 期望的潜次当前方案版本；与冻结版本不一致则拒绝（来源/方案已变）
 * @param surfacedAt      确认出水时刻（UTC，精确时刻）；为空取服务时钟
 */
public record ControlledSurfaceRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @PositiveOrZero int expectedVersion,
        Instant surfacedAt) {
}
