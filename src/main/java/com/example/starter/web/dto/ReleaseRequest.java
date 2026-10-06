package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.starter.domain.RetestResult;

/**
 * 解除限功率请求：须运行与监测两名不同人员确认，并随请求原子提交复测记录。
 */
public record ReleaseRequest(
        @NotBlank String operationStaffId,
        @NotBlank String monitoringStaffId,
        @NotNull RetestResult retestResult,
        @NotNull Instant retestSampledAt,
        @NotBlank String retestEvidenceRef) {
}
