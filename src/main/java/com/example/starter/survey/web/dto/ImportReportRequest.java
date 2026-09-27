package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 导入外部船旗国检查报告（首修订）；requestId 幂等。
 *
 * @param reportNo    报告编号（全局唯一；同编号同内容重复导入不新增，异内容冲突）
 * @param shipImo     本船 IMO（须已登记）
 * @param flagCode    船旗代码（合成）
 * @param completedAt 检查完成时刻（UTC，精确时刻，不得晚于当前时刻）
 * @param attestation 签认标记（仅接受合成标记）
 * @param items       逐项结论（非空，项目代码不得重复）
 */
public record ImportReportRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max = 64) String reportNo,
        @NotBlank String shipImo,
        @NotBlank @Size(max = 8) String flagCode,
        @NotNull Instant completedAt,
        @NotBlank @Size(max = 32) String attestation,
        @NotNull @NotEmpty @Valid List<ReportItemInput> items
) {
}
