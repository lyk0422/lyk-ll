package com.example.starter.trial.api;

/**
 * 揭盲确认/查询结果。
 *
 * @param requestId          揭盲申请编号
 * @param trialId            试验编号
 * @param subjectScreenNo    受试者筛选号
 * @param randomizationNo    随机号
 * @param status             PENDING（单人确认，未生效）/ COMPLETED（已生效终态）
 * @param confirmerA         第一名确认人工号
 * @param confirmerB         第二名确认人工号（未完成时为 null）
 * @param unblindedGroupCode 揭盲后返回的真实组别名（TREATMENT 试验组 / CONTROL 对照组）；未生效时为 null
 * @param replayed           是否为终态幂等重放
 */
public record UnblindingResponse(
        String requestId,
        String trialId,
        String subjectScreenNo,
        long randomizationNo,
        String status,
        String confirmerA,
        String confirmerB,
        String unblindedGroupCode,
        boolean replayed) {
}
