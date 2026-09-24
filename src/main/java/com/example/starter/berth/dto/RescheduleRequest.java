package com.example.starter.berth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 改期请求。已批准/已生效窗口改期视为新申请，继承原窗口的船名、泊位、吃水与引航员数，
 * 仅变更起止时刻，并重新走完整冲突校验（与原窗口自身不算冲突）。
 *
 * @param requestId 新申请的幂等请求键
 * @param startAt   新的预计靠泊开始时刻（UTC，ISO-8601）
 * @param endAt     新的预计靠泊结束时刻（UTC，ISO-8601）
 */
public record RescheduleRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId,

        @NotNull(message = "新的预计靠泊开始时刻不能为空")
        Instant startAt,

        @NotNull(message = "新的预计靠泊结束时刻不能为空")
        Instant endAt) {
}
