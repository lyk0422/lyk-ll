package com.example.starter.waterlogging.api;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复盘归档请求；归档为终态，重复归档返回幂等成功。
 *
 * @param requestId    幂等键
 * @param planRequestId 目标预降单的预警 requestId
 * @param archiveTime  归档时刻
 * @param remark       复盘备注（可空）
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String planRequestId,
        @NotNull LocalDateTime archiveTime,
        String remark) {
}
