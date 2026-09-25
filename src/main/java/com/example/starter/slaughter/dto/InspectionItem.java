package com.example.starter.slaughter.dto;

import com.example.starter.slaughter.domain.InspectionResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单头同步检验项。
 *
 * @param headNo 头只编号
 * @param result 同步检验结果 NORMAL/SUSPECT
 */
public record InspectionItem(
        @NotBlank String headNo,
        @NotNull InspectionResult result) {
}
