package com.example.starter.dto;

import com.example.starter.model.ReinspectionResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复检登记请求：复检合格则同事务结案并撤销限功率，不合格则维持限功率。
 */
public record CreateReinspectionRequest(
        @NotBlank String requestId,
        @NotNull ReinspectionResult result) {
}
