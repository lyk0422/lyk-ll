package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 分段计划取消请求：仅暂停中的计划可取消；取消释放机组占用，不自动恢复原功率。
 *
 * @param requestId 幂等键，覆盖全部业务字段
 */
public record StagedPlanCancelRequest(
        @NotBlank String requestId) {
}
