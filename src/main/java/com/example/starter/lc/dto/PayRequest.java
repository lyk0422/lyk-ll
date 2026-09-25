package com.example.starter.lc.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 银行付款指令；付款承兑为终态，重复指令返回首次结果且不重复扣减额度。
 *
 * @param requestId 幂等键
 * @param batchNo   交单批次号
 * @param paidAt    付款时刻（UTC），为空取服务当前时间
 */
public record PayRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        Instant paidAt) {
}
