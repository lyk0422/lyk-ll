package com.example.starter.trial.api.dto;

/**
 * 入组结果（盲态）：随机号与盲态编码，不暴露真实组别。
 *
 * @param randomNo    中心内递增发放的随机号
 * @param blindedCode 盲态编码 BLIND-A / BLIND-B
 * @param status      ENROLLED（未揭盲）/ UNBLINDED（已揭盲终态）
 */
public record EnrollResponse(
        String trialCode,
        String centerCode,
        String screeningNo,
        long randomNo,
        String blindedCode,
        String status) {
}
