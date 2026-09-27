package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 历史水下记录更正请求：不追改已关闭潜次，另记独立差异。
 *
 * @param requestId            本次更正操作幂等键
 * @param diveRequestId        被更正的已关闭潜次 requestId
 * @param diverCode            被更正记录的潜水员工号
 * @param phase                被更正阶段 DESCENT/BOTTOM/ASCENT
 * @param expectedVersion      期望的该潜次当前方案版本（乐观并发，版本不符拒绝）
 * @param correctedDurationMin 更正后该段历时，分钟（非负整数）
 */
public record RecordCorrectionRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotBlank String diverCode,
        @NotBlank String phase,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull @PositiveOrZero Integer correctedDurationMin) {
}
