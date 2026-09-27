package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 补偿批准请求：受控缸次投料前冻结每种染料的批次、系数与补偿目标，不修改已发布基础配方。
 */
public record ApproveCompensationRequest(
        @NotBlank String requestId,
        @NotBlank String approvalNo,
        @NotBlank String lotNo,
        @NotEmpty @Valid List<CompensationItemInput> items) {
}
