package com.example.starter.cosmetic.child.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 儿童安全报告请求条目（按档位独立，成人功效报告不能替代）。
 *
 * @param reportNo         报告编号
 * @param bandCode         报告所属档位编码：BAND_0_36 / BAND_37_144
 * @param coverLowerMonths 报告明确覆盖的年龄下限（整数月，含，0~144）
 * @param coverUpperMonths 报告明确覆盖的年龄上限（整数月，含，0~144）
 * @param conclusionText   报告结论摘要文本
 */
public record ChildSafetyReportRequest(
        @NotBlank String reportNo,
        @NotBlank String bandCode,
        @NotNull @Min(0) @Max(144) Integer coverLowerMonths,
        @NotNull @Min(0) @Max(144) Integer coverUpperMonths,
        String conclusionText) {
}
