package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建申报批次（数据采集后的关联校验环节）：建批次、关联单证并立即执行换汇成本校验。
 *
 * @param requestId   幂等键
 * @param batchNo     批次业务编号
 * @param declareDate 申报日（UTC 日界），决定退税率文库版本
 * @param documentIds 本次关联的已采集单证 ID 列表
 */
public record CreateBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull LocalDate declareDate,
        @NotEmpty List<@NotNull Long> documentIds
) {
}
