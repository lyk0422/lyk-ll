package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 最终结案请求。同一赔案只允许一次最终结案；重复结案返回首次结果。
 *
 * @param requestId 幂等键（可空；结案终态本身按赔案号幂等）
 * @param paidAmount 实际赔付金额（元）
 */
public record CloseClaimRequest(String requestId, @NotNull BigDecimal paidAmount) {
}
