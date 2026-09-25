package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 批次申报请求。
 *
 * @param requestId      幂等键
 * @param lotNo          批次业务编号
 * @param varietyCode    品种编码（须存在于品种档案）
 * @param plotCode       地块编码（须存在于地块档案）
 * @param declaredGrade  申报等级：ORIGINAL=原种，IMPROVED=良种
 * @param labelQuantity  申报标签数量（张），发证核减基数
 * @param applicant      申报人（合成数据）
 */
public record DeclareLotRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String lotNo,
        @NotBlank @Size(max = 64) String varietyCode,
        @NotBlank @Size(max = 64) String plotCode,
        @NotNull @Pattern(regexp = "ORIGINAL|IMPROVED") String declaredGrade,
        @Positive @NotNull Integer labelQuantity,
        @Size(max = 64) String applicant
) {
}
