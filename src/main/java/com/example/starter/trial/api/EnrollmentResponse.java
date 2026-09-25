package com.example.starter.trial.api;

/**
 * 入组结果（盲态）：只返回随机号与盲态组别编码，不暴露试验/对照身份。
 *
 * @param randomizationNo   中心内递增发放的随机号
 * @param blindedGroupCode  盲态组别编码（A/B）
 * @param replayed          是否为幂等重放（true=返回首次分配结果）
 */
public record EnrollmentResponse(long randomizationNo, String blindedGroupCode, boolean replayed) {
}
