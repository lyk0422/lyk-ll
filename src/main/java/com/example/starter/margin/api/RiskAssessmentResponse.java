package com.example.starter.margin.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 风控评估响应：基于账户最新已确认快照计算维持担保比例并给出处置动作。
 *
 * @param accountNo       账户号
 * @param totalAssets     最新快照总资产（元）
 * @param totalLiabilities 最新快照总负债（元）
 * @param ratio           维持担保比例（总资产/总负债，保留4位小数）
 * @param action          处置动作：NONE（不触发）/MARGIN_NOTICE（追保）/LIQUIDATE（直接强平）
 * @param noticeCreated   本次是否新生成追保通知（重复触发合并时为 false）
 * @param noticeId        未结清追保通知 ID（如有）
 * @param deadline        追保截止时间（次一交易日 15:00，UTC+8，响应以 UTC 时刻返回）
 * @param jobId           强平作业 ID（直接强平时生成 PENDING 作业）
 */
public record RiskAssessmentResponse(
        String accountNo,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal ratio,
        String action,
        boolean noticeCreated,
        Long noticeId,
        Instant deadline,
        Long jobId
) {
}
