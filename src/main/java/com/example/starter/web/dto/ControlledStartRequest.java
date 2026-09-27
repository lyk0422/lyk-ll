package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 受控潜次实际下潜请求：下潜前重查冻结来源未变，否则须重做方案。
 *
 * @param requestId       本次操作幂等键
 * @param diveRequestId   所属受控潜次 requestId
 * @param expectedVersion 期望当前方案版本；与冻结版本不一致则拒绝（旧方案不能消费新出的积分）
 * @param actualDescentAt 实际下潜时刻（UTC）；为空取服务时钟。间隔门禁与积分按此刻重查
 */
public record ControlledStartRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        Instant actualDescentAt) {
}
