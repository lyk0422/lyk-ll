package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 宰前申报请求。
 *
 * @param requestId  幂等请求标识，全局唯一
 * @param batchNo    屠宰批次号，业务唯一
 * @param species    畜种（合成数据）
 * @param farmOrigin 养殖场来源（合成数据）
 * @param headNos    头只编号清单，一一对应建立头只台账
 */
public record DeclareRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String species,
        @NotBlank String farmOrigin,
        @NotEmpty List<@NotBlank String> headNos) {
}
