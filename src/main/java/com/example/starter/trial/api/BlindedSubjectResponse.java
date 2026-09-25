package com.example.starter.trial.api;

/**
 * 盲态查询结果。
 *
 * @param trialId           试验编号
 * @param subjectScreenNo   受试者筛选号
 * @param siteCode          中心编号
 * @param randomizationNo   随机号
 * @param blindedGroupCode  盲态组别编码（A/B）
 * @param status            受试者状态（ENROLLED/UNBLINDED）
 */
public record BlindedSubjectResponse(
        String trialId,
        String subjectScreenNo,
        String siteCode,
        long randomizationNo,
        String blindedGroupCode,
        String status) {
}
