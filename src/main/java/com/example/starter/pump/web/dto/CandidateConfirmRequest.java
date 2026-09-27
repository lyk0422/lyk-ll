package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 切换候选确认请求：携带期望版本与各站任务生效的精确时刻。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望的分区工况版本（须与候选固定版本一致）
 * @param switchTime      各站启停任务生效时刻（Asia/Shanghai）
 */
public record CandidateConfirmRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull LocalDateTime switchTime
) {
}
