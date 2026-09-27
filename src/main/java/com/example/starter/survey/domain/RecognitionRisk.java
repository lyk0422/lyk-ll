package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 已签发检验的历史互认证据被新报告修订影响时登记的风险记录（历史证据不改动）。
 *
 * @param id          主键
 * @param bindingId   受影响的历史绑定主键
 * @param reportNo    报告编号
 * @param newRevision 触发风险的新修订号
 * @param note        风险说明
 * @param createdAt   登记时刻（UTC）
 */
public record RecognitionRisk(
        Long id,
        Long bindingId,
        String reportNo,
        int newRevision,
        String note,
        Instant createdAt
) {
}
