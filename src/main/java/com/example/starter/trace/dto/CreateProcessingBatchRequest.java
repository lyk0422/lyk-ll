package com.example.starter.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 创建加工批次请求。引用的采收批必须全部为已放行状态，引用与放行判定在同一事务内校验。
 */
public record CreateProcessingBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotEmpty List<Long> harvestBatchIds) {
}
