package com.example.starter.lc.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交单批次状态响应。时间字段均为 UTC，未发生的事件对应字段为 null。
 *
 * @param replayed 本次是否命中 requestId 幂等重放（返回首次结果）
 */
public record PresentationResponse(
        String presentationNo,
        String lcNo,
        Integer batchSeq,
        String status,
        BigDecimal drawAmount,
        LocalDateTime receivedAtUtc,
        LocalDateTime deadlineUtc,
        LocalDateTime noticeAtUtc,
        LocalDateTime acceptedAtUtc,
        LocalDateTime paidAtUtc,
        LocalDateTime refundedAtUtc,
        List<DiscrepancyView> discrepancies,
        boolean replayed) {

    /**
     * 不符点视图。
     */
    public record DiscrepancyView(String docType, String code, String description, Integer seqNo) {
    }
}
