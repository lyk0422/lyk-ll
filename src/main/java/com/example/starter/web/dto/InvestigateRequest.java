package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 初查处置请求。requestId 为幂等键；confirmed=true 时同事务下达限功率指令。
 */
public record InvestigateRequest(
        @NotBlank String requestId,
        @NotNull Boolean confirmed,
        String note) {
}
