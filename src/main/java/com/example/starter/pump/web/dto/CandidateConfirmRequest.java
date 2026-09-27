package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * 强排候选确认（联锁切换）。
 *
 * <p>事务内校验：旧分区序列版本（候选期望的在强排序列仍在强排）、所有站无其他占用、
 * 闸门合成到位回执齐全；全部满足才原子启用新序列、终结旧序列并写各站任务，任一不到位零切换。
 *
 * @param requestId       幂等键
 * @param sequenceId      候选序列ID
 * @param expectedVersion 期望的分区工况配置版本（>=1）；与当前不一致则409并返回当前版本
 * @param confirmTime     确认精确UTC时刻
 */
public record CandidateConfirmRequest(
        @NotBlank String requestId,
        @NotNull Long sequenceId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull OffsetDateTime confirmTime
) {
}
