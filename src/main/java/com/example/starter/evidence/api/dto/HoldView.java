package com.example.starter.evidence.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 法律保全单视图。
 *
 * @param holdId      保全单标识
 * @param caseId      案件编号
 * @param status      状态 ACTIVE/RELEASED
 * @param version     保全单版本
 * @param evidenceIds 覆盖的证据标识集合
 * @param createdAt   创建时间（UTC）
 * @param releasedAt  解除时间（UTC），可空
 * @param replayed    本次请求是否为幂等重放
 */
public record HoldView(
        String holdId,
        String caseId,
        String status,
        int version,
        List<String> evidenceIds,
        Instant createdAt,
        Instant releasedAt,
        boolean replayed) {
}
