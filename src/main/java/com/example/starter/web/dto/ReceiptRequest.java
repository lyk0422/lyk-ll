package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 无害化处理回执登记请求。
 *
 * @param requestId 幂等请求标识
 * @param receiptNo 回执编号，批次内唯一
 * @param category  回执对应判定类别：RENDERING 化制 / DESTRUCTION 销毁
 * @param headCount 回执载明处理头只数，出厂核销时须与同类判定数恰等
 * @param processor 无害化处理单位（合成数据）
 */
public record ReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String receiptNo,
        @NotBlank String category,
        @NotNull @Positive Integer headCount,
        @NotBlank String processor) {
}
