package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 协查结案请求：结论回写聚集事件状态，与事件推进在同一事务内完成。
 *
 * @param conclusion 协查结论：CONFIRMED 确认聚集 / EXCLUDED 排除聚集
 * @param operator   结案操作人，非空
 * @param note       结案说明，可空
 */
public record ConcludeInvestigationRequest(
        @NotBlank @Pattern(regexp = "CONFIRMED|EXCLUDED") String conclusion,
        @NotBlank String operator,
        String note
) {
}
