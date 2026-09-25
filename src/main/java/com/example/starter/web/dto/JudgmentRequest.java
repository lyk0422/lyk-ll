package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 判定分流请求：一次覆盖批次全部头只，与库存标识在同一事务内完成。
 *
 * @param requestId 幂等请求标识
 * @param judgments 逐头只判定明细
 */
public record JudgmentRequest(
        @NotBlank String requestId,
        @NotEmpty List<@Valid JudgmentItem> judgments) {

    /**
     * 单头只判定项。
     *
     * @param headNo          头只编号
     * @param category        判定类别：QUALIFIED/CONDITIONAL/RENDERING/DESTRUCTION
     * @param highTemp        有条件食用是否高温处理；类别为 CONDITIONAL 时须为 true
     * @param circulationArea 有条件食用限定流通区域；类别为 CONDITIONAL 时必填
     */
    public record JudgmentItem(
            @NotBlank String headNo,
            @NotBlank String category,
            Boolean highTemp,
            String circulationArea) {
    }
}
