package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 儿童备案单个分龄档材料请求条目。
 *
 * <p>每档需独立安全报告与适用年龄标识；报告必须明确覆盖申请年龄范围在该档内的部分。</p>
 *
 * @param bandCode            分龄档编码（BAND_0_36 / BAND_37_144）
 * @param reportNo            儿童安全报告编号（独立于成人功效评价试验报告）
 * @param coverageLowerMonths 报告明确覆盖的年龄下限（整数月，含，0~144）
 * @param coverageUpperMonths 报告明确覆盖的年龄上限（整数月，含，0~144）
 * @param conclusionSupported 报告结论是否支持该年龄段使用安全
 * @param conclusionText      报告结论文本
 * @param labelText           该档适用年龄标识文本
 */
public record ChildBandDossierRequest(
        @NotBlank String bandCode,
        @NotBlank String reportNo,
        @NotNull @Min(0) @Max(144) Integer coverageLowerMonths,
        @NotNull @Min(0) @Max(144) Integer coverageUpperMonths,
        @NotNull Boolean conclusionSupported,
        String conclusionText,
        @NotBlank String labelText) {
}
