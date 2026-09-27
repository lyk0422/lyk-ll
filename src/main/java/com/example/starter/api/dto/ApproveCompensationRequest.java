package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 补偿批准请求：为受控缸次的每种配方染料指定批次与系数版本；
 * 批准冻结批次、系数与补偿目标，不修改已发布基础配方。
 */
public record ApproveCompensationRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<CompensationItemInput> items) {
}
