package com.example.starter.lc.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 交单项下单据。
 *
 * @param docType 单据类型，如 BILL_OF_LADING、COMMERCIAL_INVOICE
 * @param docRef  单据编号/标识
 */
public record DocumentItem(
        @NotBlank String docType,
        @NotBlank String docRef) {
}
