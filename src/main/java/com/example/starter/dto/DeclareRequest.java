package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批签发申报请求（申报→资料审查入口，同时携带合成检验链）。
 *
 * @param requestId      幂等键
 * @param enterpriseName 申报企业
 * @param productName    疫苗品种
 * @param batchNo        批号（全局唯一）
 * @param documents      本次申报提交的资料目录代码
 * @param inspections    本次申报随附的检验项目实测结果
 */
public record DeclareRequest(
        @NotBlank String requestId,
        @NotBlank String enterpriseName,
        @NotBlank String productName,
        @NotBlank String batchNo,
        @NotEmpty @Valid List<@NotBlank String> documents,
        @NotEmpty @Valid List<InspectionInput> inspections
) {
}
