package com.example.starter.orchestrator.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 节点规格（合成数据，不触发真实进程）。
 *
 * @param key 节点标识，计划内唯一
 * @param params 节点参数
 * @param delayMillis 模拟启动延迟，单位毫秒
 * @param condition 条件谓词，可为 null
 * @param dependsOn 显式依赖节点标识列表
 * @param healthCheck 合成健康检查结果：PASS 或 FAIL
 * @param compensation 补偿/回滚动作名，可为 null
 */
public record NodeSpec(
        @NotBlank String key,
        Map<String, String> params,
        @Min(0) long delayMillis,
        @Valid ConditionSpec condition,
        List<String> dependsOn,
        @NotBlank @Pattern(regexp = "PASS|FAIL") String healthCheck,
        String compensation) {
}
