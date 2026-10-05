package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 结束批次请求。requestUid 为幂等键；expectedVersion 为乐观锁版本校验，
 * 指纹覆盖 requestUid、批次、期望版本与精确结束时刻。
 *
 * @param closeTime 精确结束时刻（Asia/Shanghai），纳入幂等指纹
 */
public record CloseBatchRequest(
        @NotBlank String requestUid,
        @NotBlank String batchId,
        @NotNull Integer expectedVersion,
        @NotNull LocalDateTime closeTime
) {
}
