package com.example.starter.museum.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 续展审批请求。仅可在届满前 60 个自然日起申请一次（恰第 60 日受理，更早拒绝）；
 * 续展后总期限不超过 3 年，续展增量不超过原期限一半。
 */
public record RenewRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo,
        @NotNull LocalDate newEndDate,
        boolean approved
) {
}
