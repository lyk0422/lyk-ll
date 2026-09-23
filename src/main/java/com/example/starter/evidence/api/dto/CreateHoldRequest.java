package com.example.starter.evidence.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 法律保全创建请求。
 *
 * @param requestId       请求幂等键
 * @param caseId          案件编号
 * @param evidenceIds     显式证据标识集合
 * @param expectedVersion 预期领域版本，可空表示不校验
 */
public record CreateHoldRequest(
        @NotBlank String requestId,
        @NotBlank String caseId,
        @NotEmpty List<@NotBlank String> evidenceIds,
        Long expectedVersion) {
}
