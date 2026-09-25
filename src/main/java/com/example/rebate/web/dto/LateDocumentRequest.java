package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 结案后迟到单证登记请求。只记迟到事件，不参与关联。
 */
public record LateDocumentRequest(
        @NotBlank String requestId,
        String declarationNo,
        String invoiceNo,
        @NotBlank String note) {
}
