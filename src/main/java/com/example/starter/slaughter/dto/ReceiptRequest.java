package com.example.starter.slaughter.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 无害化处理回执登记请求。
 *
 * @param requestId 幂等键
 * @param receiptNo 回执编号（合成数据）
 * @param headNos   回执覆盖的头只编号，须与同类别判定头只勾稽一致
 */
public record ReceiptRequest(
        @jakarta.validation.constraints.NotBlank String requestId,
        @jakarta.validation.constraints.NotBlank String receiptNo,
        @NotEmpty List<@jakarta.validation.constraints.NotBlank String> headNos) {
}
