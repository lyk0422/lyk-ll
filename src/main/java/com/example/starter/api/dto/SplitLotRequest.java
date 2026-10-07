package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 分缸请求：将已成功投料且未色判、未归档、未曾分缸的来源缸次拆分为 2-4 个子缸。
 * expectedVersion 为分缸聚合乐观锁版本：新增分缸版本 1 起，必须携带 1，旧版 409；
 * requestId 幂等指纹覆盖全部目标缸、重量与版本，同参重放原响应，异参 409，失败不占键。
 */
public record SplitLotRequest(
        @NotBlank String requestId,
        @NotBlank String sourceLotNo,
        @NotNull Integer expectedVersion,
        @NotEmpty @Valid List<SplitChildInput> children) {
}
