package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 关联校验请求：按报关单号集合创建申报批次并执行关联校验。
 *
 * @param batchNo 客户端指定的批次号（业务字段，纳入指纹）
 * @param declarationNos 参与本批次的报关单号集合
 * @param declareDate 申报日（UTC 日界），退税率文库版本取值依据
 */
public record CheckRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotEmpty List<@NotBlank String> declarationNos,
        @NotNull LocalDate declareDate) {
}
