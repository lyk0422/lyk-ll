package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 报告更正：生成新修订并原子使未签发检验的旧互认失效；requestId 幂等。
 *
 * @param reportNo        报告编号
 * @param expectedVersion 当前有效修订的实体版本（从 1 开始，成功更正 +1）
 * @param completedAt     更正后的检查完成时刻（UTC，精确时刻）
 * @param attestation     签认标记（仅接受合成标记）
 * @param items           更正后的逐项结论全集（非空，项目代码不得重复）
 */
public record CorrectReportRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max = 64) String reportNo,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant completedAt,
        @NotBlank @Size(max = 32) String attestation,
        @NotNull @NotEmpty @Valid List<ReportItemInput> items
) {
}
