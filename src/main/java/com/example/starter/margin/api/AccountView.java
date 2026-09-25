package com.example.starter.margin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 账户全景视图：基本信息、最新已确认快照、未结清通知与强平作业。
 */
public record AccountView(
        String accountNo,
        String clientName,
        String currency,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal ratio,
        String openNoticeStatus,
        Instant deadline,
        String pendingJobStatus,
        Long pendingJobId,
        String executedJobStatus,
        Long executedJobId
) {
}
