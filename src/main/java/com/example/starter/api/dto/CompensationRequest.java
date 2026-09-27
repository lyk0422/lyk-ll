package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 受控缸次效价补偿审批请求：为配方中每种染料指定批次与系数版本。
 * 审批冻结批次、系数与补偿目标克重（原目标 / 系数，四舍五入到三位小数），不修改已发布基础配方。
 */
public record CompensationRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotNull @Min(1) Integer expectedVersion,
        @NotEmpty @Valid List<CompensationItemInput> items) {
}
