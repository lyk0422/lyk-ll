package com.example.starter.flood.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 泄流方案创建请求；孔口组合泄量之和不得超过下游安全泄量（恰等合法）。
 *
 * @param requestId 幂等键
 * @param warningNo 关联处置链预警编号
 * @param routingNo 依据的调洪演算编号
 * @param openings 孔口组合
 */
public record PlanRequest(
        @NotBlank String requestId,
        @NotBlank String warningNo,
        @NotBlank String routingNo,
        @NotEmpty @Valid List<OpeningItem> openings) {
}
