package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 历史水下记录独立更正请求：只记差异，不追改已关闭潜次的原记录、台账与积分。
 *
 * @param requestId       本次更正操作的幂等键
 * @param diveRequestId   被更正记录所属潜次
 * @param recordId        被更正的水下记录 id
 * @param correctedDurationMin 更正后的历时分钟（正整数，合成值）
 * @param expectedVersion 期望的潜次方案版本（被更正记录所属版本），不一致 409
 * @param correctedAt     更正精确时刻（UTC）；为空取服务时钟
 * @param reason          更正说明，允许包含任意分隔符文本（指纹仍无歧义）
 */
public record RecordCorrectionRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotNull Long recordId,
        @Positive int correctedDurationMin,
        @PositiveOrZero int expectedVersion,
        Instant correctedAt,
        @NotBlank String reason) {
}
