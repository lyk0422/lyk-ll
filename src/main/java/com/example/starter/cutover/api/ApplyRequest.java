package com.example.starter.cutover.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请请求；requestId 为幂等键，指纹覆盖本对象全部业务字段。
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String deviceCode,
        @NotBlank String title,
        @NotBlank String priority,
        @NotNull LocalDateTime windowStart,
        @NotNull LocalDateTime windowEnd,
        @NotEmpty @Valid List<StepSpec> steps
) {

    /**
     * 步骤定义：seq 从 1 连续递增；stepType 取 NORMAL/VERIFY。
     */
    public record StepSpec(
            @NotNull Integer seq,
            @NotBlank String name,
            @NotBlank String stepType
    ) {
    }
}
