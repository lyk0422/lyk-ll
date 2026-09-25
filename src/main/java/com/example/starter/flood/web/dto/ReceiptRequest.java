package com.example.starter.flood.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 泄流回执请求；实际泄量与方案偏差超 10%（恰 10% 一致）必须填写原因说明。
 *
 * @param requestId 回执幂等键
 * @param operator 回执提交人（现场执行人）
 * @param actualOpenings 实际孔口组合与开度/泄量
 * @param deviationReason 偏差原因，偏差比例 &gt; 10% 时必填
 */
public record ReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotEmpty @Valid List<OpeningItem> actualOpenings,
        String deviationReason) {
}
