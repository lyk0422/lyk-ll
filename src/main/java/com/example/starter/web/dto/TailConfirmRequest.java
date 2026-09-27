package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 列尾一致性确认请求；须两名不同人员分别确认当前尾车与编组版本。
 *
 * @param requestId      幂等请求键
 * @param confirmer      确认人（合成数据）
 * @param tailCarNo      确认人看到的当前尾车车辆号，须与实际一致
 * @param consistVersion 确认人看到的当前编组版本，须与实际一致
 */
public record TailConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String confirmer,
        @NotBlank String tailCarNo,
        @NotNull @Positive Integer consistVersion) {
}
