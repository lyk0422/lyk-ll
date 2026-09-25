package com.example.starter.transition.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 过渡卷复议申请；仅首次检验恰达/达到正品门槛且尚未复议时可改判正品一次。
 *
 * @param requestId 幂等键
 * @param rollNo    卷号
 */
public record AppealRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo
) {
}
