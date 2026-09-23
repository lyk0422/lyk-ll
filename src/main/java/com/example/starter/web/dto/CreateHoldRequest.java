package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * 法律保全创建请求：以案件和显式证据 ID 集合创建，必须携带 requestId 与预期版本。
 *
 * @param expectedVersion 保全全局预期版本，必须与创建时刻当前版本一致，否则 409
 */
public record CreateHoldRequest(
        @NotBlank String caseId,
        @NotEmpty Set<String> evidenceIds,
        @NotBlank String requestId,
        @NotNull Long expectedVersion
) {
}
