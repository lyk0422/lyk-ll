package com.example.starter.web.dto;

import com.example.starter.model.MaintenanceAcceptanceRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 检修验收请求：机务/检修两个不同人员分别验收，验收时刻不得早于窗口 end。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的当前版本（用于并发一致性校验）
 * @param role            验收岗位：MECHANICAL 机务 / REPAIR 检修
 * @param operatorId      验收人员标识，两个岗位不得为同一人
 * @param opinion         验收人员意见
 */
public record MaintenanceAcceptanceRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull MaintenanceAcceptanceRole role,
        @NotBlank String operatorId,
        @NotBlank String opinion) {
}
