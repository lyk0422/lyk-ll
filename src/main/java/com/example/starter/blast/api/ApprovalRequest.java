package com.example.starter.blast.api;

import jakarta.validation.constraints.NotNull;

/**
 * 审批请求体。
 *
 * @param reviewer 审批人
 * @param approved true 批准，false 驳回
 * @param reason   驳回原因（驳回时建议填写）
 */
public record ApprovalRequest(
        String reviewer,
        @NotNull Boolean approved,
        String reason) {
}
