package com.example.starter.cutover.dto;

import com.example.starter.cutover.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请请求。requestId 为申请幂等键；指纹覆盖 requestId 与全部业务字段。
 *
 * @param windowStart 窗口开始时间（含），Asia/Shanghai
 * @param windowEnd   窗口结束时间（不含），Asia/Shanghai，窗口最长 4 小时（恰 4 小时合法）
 */
public record CreateCutoverRequest(
        @NotBlank String requestId,
        @NotBlank String title,
        @NotBlank String deviceCode,
        @NotNull Priority priority,
        @NotNull LocalDateTime windowStart,
        @NotNull LocalDateTime windowEnd,
        @NotEmpty @Valid List<StepDefinition> steps
) {
}
