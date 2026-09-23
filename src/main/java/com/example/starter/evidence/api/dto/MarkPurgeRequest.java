package com.example.starter.evidence.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 清除标记请求。
 *
 * @param requestId       请求幂等键
 * @param evidenceIds     显式候选证据标识集合
 * @param cutoff          到期截止时间（UTC），仅到期时间不晚于该值的证据可被标记
 * @param expectedVersion 预期领域版本，可空表示不校验
 */
public record MarkPurgeRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotBlank String> evidenceIds,
        @NotNull Instant cutoff,
        Long expectedVersion) {
}
