package com.example.starter.lc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 交单单据条目（合成数据）。
 *
 * @param docType     单据类型，如 BILL_OF_LADING/COMMERCIAL_INVOICE/PACKING_LIST
 * @param docNo       单据编号
 * @param description 单据描述
 */
public record DocItem(
        @NotBlank String docType,
        @NotBlank String docNo,
        String description) {
}
