package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 出栏申请。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不改变状态。
 *
 * <p>休药期届满日（UTC 日历日）当日 24:00（即次日 00:00 UTC）之后方可出栏；
 * 休药期内申请一律拒绝并提示最早合法出栏时刻。出栏为终态，重复申请返回幂等成功。</p>
 *
 * @param requestId      幂等键
 * @param batchNo        养殖批次号
 * @param applyAtUtc     申请出栏时刻（UTC）
 */
public record SlaughterRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotNull(message = "出栏申请时刻不能为空")
        Instant applyAtUtc
) {
}
