package com.example.starter.trajectory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 规则版本发布请求，{@code ruleVersion} 一经发布不可变。
 *
 * @param ruleVersion 规则版本号，全局唯一
 * @param config      规则阈值配置
 */
public record CreateRuleVersionRequest(
        @NotBlank String ruleVersion,
        @NotNull RuleConfig config) {
}
