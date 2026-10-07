package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 检修分段清空登记请求（生效后逐段登记）。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望版本号，与当前版本不一致返回 409
 * @param segmentId       已清空分段 id，须在工单清单内
 * @param operatorId      清空操作人标识
 * @param note            清空说明，可空
 */
public record MaintenanceClearanceRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Long segmentId,
        @NotBlank String operatorId,
        @Size(max = 256) String note) {
}
