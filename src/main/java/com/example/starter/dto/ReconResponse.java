package com.example.starter.dto;

import com.example.starter.entity.ReconDiff;
import java.time.LocalDate;
import java.util.List;

/** 对账结果：三类差异笔数与差异明细。 */
public record ReconResponse(
        String fileNo,
        LocalDate batchDate,
        int localExtraCount,
        int channelExtraCount,
        int amountMismatchCount,
        List<ReconDiff> diffs) {
}
