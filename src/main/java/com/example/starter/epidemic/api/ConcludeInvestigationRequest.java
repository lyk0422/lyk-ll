package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 协查结论回写请求：结论与聚集事件状态推进在同一事务内完成。
 *
 * @param conclusion 协查结论，非空
 * @param result     事件推进结果：CONFIRMED 确认聚集 / EXCLUDED 排除聚集
 */
public record ConcludeInvestigationRequest(
        @NotBlank String conclusion,
        @NotBlank @Pattern(regexp = "CONFIRMED|EXCLUDED") String result) {
}
