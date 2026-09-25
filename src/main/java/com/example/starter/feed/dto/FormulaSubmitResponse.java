package com.example.starter.feed.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方提交/重放响应。
 *
 * @param formulaCode   配方编码
 * @param version       新版本号（从 1 递增）
 * @param status        SUBMITTED / REJECTED
 * @param targetSpecies 目标动物种类
 * @param ingredients   原料清单
 * @param drugs         药物清单
 * @param rejectReason  合规拒绝原因；通过为 null
 * @param replayed      是否为同键同参的幂等重放（返回首次结果）
 * @param createdAtUtc  首次提交时刻（UTC）
 */
public record FormulaSubmitResponse(
        String formulaCode,
        int version,
        String status,
        String targetSpecies,
        List<RatioItem> ingredients,
        List<DrugView> drugs,
        String rejectReason,
        boolean replayed,
        Instant createdAtUtc
) {
    /**
     * 药物项视图：携带字典上限与休药天数供调用方复算。
     */
    public record DrugView(String code, BigDecimal ratio, BigDecimal maxRatio, Integer withdrawalDays) {
    }
}
