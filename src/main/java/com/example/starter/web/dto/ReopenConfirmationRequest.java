package com.example.starter.web.dto;

import com.example.starter.model.ReopenRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复开双岗确认请求。
 *
 * @param requestId  幂等请求标识
 * @param role       确认岗位：OPERATIONS 运营 / MAINTENANCE 机务
 * @param operatorId 确认人标识
 */
public record ReopenConfirmationRequest(
        @NotBlank String requestId,
        @NotNull ReopenRole role,
        @NotBlank String operatorId) {
}
