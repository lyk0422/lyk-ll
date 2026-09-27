package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 导入外部船旗国检查报告，或对同编号报告提交更正（生成新修订）。
 *
 * @param reportNo         报告编号，全局唯一
 * @param expectedRevision 客户端持有的该报告当前修订号；首次导入传 0，更正须传当前修订号
 * @param shipImo          本船 IMO
 * @param flagCode         船旗代码
 * @param completedAt      UTC 检查完成时刻（精确瞬时）
 * @param signatureMarker  合成签认标记
 * @param items            逐项结论（项目代码不得重复，且不能为空）
 */
public record ImportReportRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotNull Integer expectedRevision,
        @NotBlank String shipImo,
        @NotBlank String flagCode,
        @NotNull Instant completedAt,
        @NotBlank String signatureMarker,
        @NotEmpty @Valid List<ReportItemInput> items
) {
}
