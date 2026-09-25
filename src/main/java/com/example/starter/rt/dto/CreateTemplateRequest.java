package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 新建约束模板请求（合成参考数据）。
 *
 * @param templateCode 模板编码
 * @param templateName 模板名称
 * @param items        逐项剂量体积约束
 */
public record CreateTemplateRequest(
        @NotBlank(message = "不能为空") String templateCode,
        @NotBlank(message = "不能为空") String templateName,
        @NotEmpty(message = "至少包含一项约束") @Valid List<ConstraintItemRequest> items) {
}
