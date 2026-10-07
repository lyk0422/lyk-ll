package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输证据提交请求（requestId 幂等）：登记新版本运输片段与读数。
 * 首次登记 expectedVersion 传 0（版本从 1 起）；补录/更正传当前版本，成功加 1，旧版 409。
 * 证据不合格不拒绝写入：台账记录缺口/越界点并置 UNQUALIFIED，签发门禁据此拒签。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望的台账版本：0 表示首次登记，否则须等于当前版本
 * @param submittedAt     本次证据提交精确时刻（UTC，读数不得晚于该时刻，纳入幂等指纹）
 * @param segments        1 至 5 个顺序运输片段（含全部读数）
 */
public record ColdChainSubmitRequest(
        @NotBlank String requestId,
        @PositiveOrZero int expectedVersion,
        @NotNull Instant submittedAt,
        @NotNull @Size(min = 1, max = 5) List<@Valid SegmentInput> segments
) {
}
