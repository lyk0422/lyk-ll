package com.example.starter.trace.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采收批详情响应，含贡献明细与检测记录。
 */
public record HarvestBatchResponse(
        Long id,
        String batchNo,
        String flowerPeriod,
        String status,
        LocalDateTime createdAt,
        List<ContributionView> contributions,
        List<TestView> tests) {

    /**
     * 贡献明细视图。
     */
    public record ContributionView(Long apiaryId, String apiaryCode, String apiaryName, BigDecimal ratio) {
    }

    /**
     * 检测记录视图。
     */
    public record TestView(Long id, String testType, String sampleNo, BigDecimal inhibitionRate,
                           String conclusion, LocalDateTime createdAt) {
    }
}
