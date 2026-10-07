package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 冷链温度读数提交（重核）请求（requestId 幂等，失败不占键）。
 * 须覆盖当前版本全部登记片段；完整证据合格即冻结读数版本并签发温证。
 *
 * @param requestId       幂等键
 * @param batchNo         受控包装子批评号（路径参数优先）
 * @param expectedVersion 调用方期望的台账版本，与当前版本不一致返回 409
 * @param segments        按片段分组的温度读数，每片须含 start 与 end 两端样本
 * @param at              本次提交精确时刻（读数不得晚于该时刻，纳入幂等指纹）
 */
public record ColdChainReadingsSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @Positive int expectedVersion,
        @NotNull List<@Valid ColdChainSegmentReadingsInput> segments,
        @NotNull Instant at
) {
}
