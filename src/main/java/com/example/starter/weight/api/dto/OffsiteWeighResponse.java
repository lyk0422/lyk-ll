package com.example.starter.weight.api.dto;

/**
 * 非现场动态称重接入响应。
 *
 * @param recordId      非现场称重记录主键；命中现场记录去重时为 null
 * @param status        接入状态：AUTO_DECIDED/MANUAL_PENDING/LATE_EVENT；命中现场记录去重为 DUPLICATE
 * @param pendingReason 人工待补原因；非待补为 null
 * @param caseNo        关联案件编号；未立案为 null
 * @param decision      自动生成的处罚决定；未生成为 null
 * @param lateEvent     是否为结案后迟到事件
 * @param replayed      是否为幂等重放（同键同参返回首次结果）
 * @param deduped       是否命中“车牌+时刻窗口”去重而未新建记录（窗口内只保留首次）
 */
public record OffsiteWeighResponse(
        Long recordId,
        String status,
        String pendingReason,
        String caseNo,
        DecisionResponse decision,
        boolean lateEvent,
        boolean replayed,
        boolean deduped) {
}
