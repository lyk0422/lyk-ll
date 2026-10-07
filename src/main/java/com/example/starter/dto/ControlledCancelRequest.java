package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 受控分段计划取消请求：仅暂停后可取消；取消释放机组占用，不自动恢复原功率。
 *
 * @param requestId       幂等键，覆盖指令与期望版本的结构化指纹
 * @param expectedVersion 期望取消的当前计划版本号，须等于最新版本
 */
public record ControlledCancelRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion) {
}
