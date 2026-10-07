package com.example.starter.web.dto;

import com.example.starter.model.AcceptanceRole;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 检修验收请求：机务与检修两岗各一次，须两个不同人员，不得早于窗口终点。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望版本号，与当前版本不一致返回 409
 * @param role            验收岗位：MECHANICAL 机务 / INSPECTION 检修
 * @param operatorId      验收人标识，两岗验收人不得相同
 * @param opinion         验收意见，可空
 */
public record MaintenanceAcceptanceRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull AcceptanceRole role,
        @NotBlank String operatorId,
        @Size(max = 256) String opinion) {
}
