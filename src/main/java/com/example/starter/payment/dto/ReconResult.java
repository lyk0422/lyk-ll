package com.example.starter.payment.dto;

/**
 * 对账执行结果：总笔数、匹配笔数与三类差异笔数。
 */
public record ReconResult(
        String batchDate,
        int totalCompared,
        int matched,
        int localExtra,
        int channelExtra,
        int amountMismatch) {
}
