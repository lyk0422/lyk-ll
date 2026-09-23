package com.example.starter.evidence.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 清除批次视图。
 *
 * @param batchId    批次标识
 * @param requestId  标记请求幂等键
 * @param status     状态 MARKED/CONFIRMED
 * @param version    批次版本
 * @param cutoff     到期截止时间（UTC）
 * @param candidates 候选清单
 * @param manifest   确认时冻结的不可变清除清单，未确认为空
 * @param replayed   本次请求是否为幂等重放
 */
public record PurgeBatchView(
        String batchId,
        String requestId,
        String status,
        int version,
        Instant cutoff,
        List<PurgeCandidateView> candidates,
        Map<String, Object> manifest,
        boolean replayed) {
}
