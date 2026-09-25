package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 交接方确认请求。单方确认不生效；双方先后确认一致后凭证生效。
 *
 * @param requestId 幂等键
 * @param voucherNo 凭证号
 * @param party 确认方：A-交出方，B-接收方
 * @param confirmer 确认人姓名（合成数据）
 */
public record ConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String voucherNo,
        @NotBlank String party,
        @NotBlank String confirmer) {
}
