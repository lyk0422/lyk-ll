package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 清除最终确认请求（两阶段清除第二阶段），按批次重复确认返回首次冻结清单。
 */
public record ConfirmPurgeRequest(
        @NotBlank String batchId
) {
}
