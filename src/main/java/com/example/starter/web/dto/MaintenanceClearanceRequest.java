package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 检修分段清空证据登记请求：生效后在途吊厢只允许按原能力逐段疏散清空并留证。
 *
 * @param requestId  幂等请求标识
 * @param segmentId  清空分段 id，须属于检修单清单
 * @param operatorId 清空确认人标识
 * @param opinion    清空情况说明（人员意见）
 */
public record MaintenanceClearanceRequest(
        @NotBlank String requestId,
        @NotNull Long segmentId,
        @NotBlank String operatorId,
        @NotBlank String opinion) {
}
