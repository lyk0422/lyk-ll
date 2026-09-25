package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 争议发起请求。生效日起 7 个自然日争议期（UTC 日界）内可受理，逾期封账。
 *
 * @param requestId 幂等键
 * @param voucherNo 凭证号
 * @param party 发起方：A-交出方，B-接收方
 * @param reason 争议理由
 */
public record DisputeRequest(
        @NotBlank String requestId,
        @NotBlank String voucherNo,
        @NotBlank String party,
        @NotBlank @Size(max = 500) String reason) {
}
