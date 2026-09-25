package com.example.starter.margin.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 追加保证金入账请求。
 *
 * @param requestId 幂等键；同一 requestId 的指纹（requestId+账户+金额+币种）必须一致
 * @param accountNo 信用账户号
 * @param amount    入账金额，单位元，必须为正数，精确到分
 * @param currency  币种（ISO 4217），须与账户记账币种一致
 */
public record DepositRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String accountNo,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}
