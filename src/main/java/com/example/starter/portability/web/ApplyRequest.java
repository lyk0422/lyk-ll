package com.example.starter.portability.web;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 携号转网申请请求。资格数据为合成数据，随申请一并提交：
 * hasUnpaidBill 是否存在欠费；contractEndDate 在网协议截止日（空表示无在网约定）；
 * lastPortedAt 上次携转完成时刻（UTC，空表示从未携转）。
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String msisdn,
        @NotBlank String donorOperator,
        @NotBlank String recipientOperator,
        @NotNull Boolean hasUnpaidBill,
        LocalDate contractEndDate,
        Instant lastPortedAt) {
}
