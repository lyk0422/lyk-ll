package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 复盘归档请求。
 *
 * @param requestId  幂等键
 * @param pumpdownId 被归档预降事件ID
 * @param archiveTime 归档时刻（Asia/Shanghai）
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotNull Long pumpdownId,
        @NotNull LocalDateTime archiveTime
) {
}
