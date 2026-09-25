package com.example.starter.slaughter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 宰前申报请求。
 *
 * @param requestId 幂等键
 * @param batchNo   屠宰批次编号（合成数据）
 * @param headNos   头只编号清单（合成数据），不得为空或重复
 */
public record DeclareRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotEmpty List<@NotBlank String> headNos) {
}
