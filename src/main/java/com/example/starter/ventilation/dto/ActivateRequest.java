package com.example.starter.ventilation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 火灾模式激活请求。
 *
 * @param requestId          激活指令幂等键
 * @param zoneCode           防火分区编码
 * @param smokeFlowDirection 烟气流向假设（合成场景标签，参与指纹）
 * @param targetCondition    目标工况（如 PURGE_MAX 强排烟）
 */
public record ActivateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String zoneCode,
        @NotBlank @Size(max = 32) String smokeFlowDirection,
        @NotBlank @Size(max = 64) String targetCondition) {
}
