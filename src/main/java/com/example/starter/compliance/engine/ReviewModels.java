package com.example.starter.compliance.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合规审查纯计算模型（无数据库依赖，可独立复算）。
 */
public final class ReviewModels {

    private ReviewModels() {
    }

    /** 审查时对某一添加剂的逐项判定明细。 */
    public record ItemResult(
            String additiveCode,
            String functionCode,
            BigDecimal directAmount,
            BigDecimal carriedAmount,
            BigDecimal totalAmount,
            BigDecimal limitAmount,
            BigDecimal usageRatio,
            boolean passed,
            String reason) {
    }

    /** 同功能添加剂并用比例判定（各用量占限量比例之和，恰等 1 合法）。 */
    public record GroupResult(
            String functionCode,
            List<String> additiveCodes,
            BigDecimal combinedRatio,
            boolean passed) {
    }

    /** 一次完整合规审查报告。 */
    public record ReviewReport(
            boolean passed,
            String categoryCode,
            List<ItemResult> items,
            List<GroupResult> groups,
            long evaluatedAt) {
    }
}
