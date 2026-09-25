package com.example.starter.bonded.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 分批到货核销请求。每批核销以 batchRequestId 幂等。
 *
 * @param batchRequestId 本批核销的幂等请求号
 * @param items 本批核销的商品项与数量
 */
public record VerifyRequest(
        @NotBlank(message = "batchRequestId 不能为空")
        String batchRequestId,
        @NotEmpty(message = "核销商品项不能为空")
        @Valid
        List<VerifyItemRequest> items) {
}
