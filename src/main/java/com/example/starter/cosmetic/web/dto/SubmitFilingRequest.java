package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 备案提交请求（首次备案）。
 *
 * <p>filingCategory=CHILD 时为儿童备案：年龄范围以整数月闭区间表示
 * （0≤下限≤上限≤144），按分龄规则拆档审查，每档需独立安全报告与适用年龄标识；
 * 缺省（null）或 ADULT 为成人备案，儿童字段必须为 null。</p>
 *
 * @param requestId      幂等键；指纹覆盖 requestId 与全部业务字段
 * @param productCode    产品编码
 * @param productName    产品名称
 * @param formulation    配方（成分及质量百分比浓度）
 * @param claims         功效宣称编码列表（合成功效目录）
 * @param reports        特殊功效对应的功效评价试验报告列表；普通化妆品可不提供
 * @param filingCategory 备案类别：ADULT（默认）/ CHILD
 * @param ageLowerMonths 儿童备案申请年龄下限（整数月，含，0~144）
 * @param ageUpperMonths 儿童备案申请年龄上限（整数月，含，0~144）
 * @param childDossiers  儿童备案分龄档材料清单（独立安全报告+适用年龄标识）
 */
public record SubmitFilingRequest(
        @NotBlank String requestId,
        @NotBlank String productCode,
        @NotBlank String productName,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @NotEmpty List<@NotBlank String> claims,
        @Valid List<TestReportRequest> reports,
        @Pattern(regexp = "ADULT|CHILD") String filingCategory,
        @Min(0) @Max(144) Integer ageLowerMonths,
        @Min(0) @Max(144) Integer ageUpperMonths,
        @Valid List<ChildBandDossierRequest> childDossiers) {

    /** 是否儿童备案。 */
    public boolean isChild() {
        return "CHILD".equals(filingCategory);
    }
}
