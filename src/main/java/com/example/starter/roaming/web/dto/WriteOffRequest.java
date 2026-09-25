package com.example.starter.roaming.web.dto;

/**
 * 差异挂账人工核销请求（同一挂账只允许核销一次）。
 *
 * @param requestId     幂等键
 * @param discrepancyNo 挂账单号
 * @param operator      核销操作人（留痕）
 * @param remark        核销说明（留痕）
 */
public record WriteOffRequest(
        String requestId,
        String discrepancyNo,
        String operator,
        String remark) {
}
