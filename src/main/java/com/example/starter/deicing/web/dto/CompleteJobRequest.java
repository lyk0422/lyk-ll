package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 作业完成登记请求体。一步法按实际作业时长结算扣减库存。
 *
 * @param requestId     申请幂等键
 * @param actualEndTime 实际作业完成时刻（UTC），不早于实际开始时刻
 */
public record CompleteJobRequest(
        @NotBlank String requestId,
        @NotNull Instant actualEndTime) {
}
