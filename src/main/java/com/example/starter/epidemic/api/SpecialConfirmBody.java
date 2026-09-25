package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 特殊订正确认请求体（流程编号来自路径参数，两名审核员必须不同）。
 *
 * @param reviewer 审核员工号
 */
public record SpecialConfirmBody(@NotBlank String reviewer) {
}
