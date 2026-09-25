package com.example.starter.pv.web;

/**
 * 关闭结果；重复关闭返回同一终态结果（幂等成功）。
 */
public record CloseResponse(
        String caseNo,
        String status,
        boolean idempotentReplay,
        CaseView caseView) {
}
