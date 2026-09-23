package com.example.starter.audit.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建任务请求。
 *
 * @param requestId      幂等键，同键同参重放原结果，同键异参返回 409
 * @param codeVersion    代码版本（必填）
 * @param configVersion  配置版本（必填）
 * @param mapVersion     地图版本（必填）
 * @param rosbagVersion  输入 rosbag 版本（必填）
 * @param seed           随机种子（必填）
 * @param metricsProfile 指标口径，默认 default；extended 会增加指标以制造口径差异（可选）
 * @param faultPlan      合成故障注入 fail:&lt;阶段&gt;/interrupt:&lt;阶段&gt;，仅首批执行生效（可选）
 */
public record CreateTaskRequest(
        @NotBlank String requestId,
        @NotBlank String codeVersion,
        @NotBlank String configVersion,
        @NotBlank String mapVersion,
        @NotBlank String rosbagVersion,
        @NotNull Long seed,
        String metricsProfile,
        String faultPlan
) {
}
