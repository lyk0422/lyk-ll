package com.example.starter.weight.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 案件响应（含文书链）。
 *
 * @param caseNo    案件编号
 * @param plateNo   车牌号
 * @param status    案件状态（OPEN/CLOSED）
 * @param closedAt  结案时间（UTC）；未结案为 null
 * @param decisions 文书链（处罚决定、撤销文书等）
 * @param replayed  是否为幂等重放（重复结案）
 */
public record CaseResponse(
        String caseNo,
        String plateNo,
        String status,
        Instant closedAt,
        List<DecisionResponse> decisions,
        boolean replayed) {
}
