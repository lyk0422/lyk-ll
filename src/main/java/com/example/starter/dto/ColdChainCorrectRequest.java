package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输台账补录/更正请求（requestId 幂等，失败不占键）。
 * 未签发前成功即版本加 1 并使旧温证资格失效，须重新提交读数重核；
 * 已签发则仅登记风险事件，不回改旧证书及核销数量。
 *
 * @param requestId       幂等键
 * @param batchNo         受控包装子批评号（路径参数优先）
 * @param expectedVersion 调用方期望的台账版本，与当前版本不一致返回 409
 * @param segments        更正后的完整顺序运输片段（1 至 5 个，替换当前版本片段定义）
 * @param at              本次更正精确时刻（纳入幂等指纹）
 */
public record ColdChainCorrectRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @Positive int expectedVersion,
        @NotNull @Size(min = 1, max = 5) List<@Valid ColdChainSegmentInput> segments,
        @NotNull Instant at
) {
}
