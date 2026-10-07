package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 受控包装子批冷链运输片段登记请求（requestId 幂等）。登记即创建运输台账（版本 1）
 * 并冻结完整运输起止；登记后该子批签发须持当前版本合格温证。
 *
 * @param requestId 幂等键
 * @param batchNo   受控包装子批评号（路径参数优先）
 * @param segments  1 至 5 个顺序运输片段，[start,end) 正长度且相邻端点完全相接
 * @param at        本次登记精确时刻（纳入幂等指纹）
 */
public record ColdChainSegmentRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @Size(min = 1, max = 5) List<@Valid ColdChainSegmentInput> segments,
        @NotNull Instant at
) {
}
