package com.example.starter.waste;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 异常核查结论请求，结论留痕于核查单。
 *
 * @param requestId 幂等键
 * @param conclusionType 结论分类：DEVICE_ERROR 设备误差 / LOADING_DIFF 装载差异
 * @param note 结论说明
 */
public record ConclusionRequest(
        @NotBlank String requestId,
        @NotNull ConclusionType conclusionType,
        @NotBlank String note) {
}
