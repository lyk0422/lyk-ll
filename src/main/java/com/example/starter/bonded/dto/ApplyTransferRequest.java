package com.example.starter.bonded.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 转关申请请求。
 *
 * @param requestId 幂等请求号
 * @param enterpriseId 申请企业编号
 * @param fromWarehouse 起运仓编号
 * @param toWarehouse 目的仓编号（不得与起运仓相同）
 * @param items 商品项明细
 */
public record ApplyTransferRequest(
        @NotBlank(message = "requestId 不能为空")
        String requestId,
        @NotBlank(message = "企业编号不能为空")
        String enterpriseId,
        @NotBlank(message = "起运仓不能为空")
        String fromWarehouse,
        @NotBlank(message = "目的仓不能为空")
        String toWarehouse,
        @NotEmpty(message = "商品项不能为空")
        @Valid
        List<ItemRequest> items) {
}
