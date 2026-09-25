package com.example.starter.lc.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 交单批次视图。
 *
 * @param batchNo      批次号
 * @param lcNo         信用证编号
 * @param beneficiary  受益人
 * @param drawAmount   本批支款金额（币种元）
 * @param status       批次状态
 * @param presentedAt  银行收到单据时间（UTC）
 * @param deadlineAt   拒付通知截止时间（UTC，第 5 个工作日 17:00）
 * @param refusedAt    拒付通知发出时间（UTC），未拒付为 null
 * @param acceptedAt   接受时间（UTC），未接受为 null
 * @param paidAt       付款时间（UTC），未付款为 null
 * @param documents    随附单据
 * @param discrepancies 已列不符点
 */
public record SubmissionResponse(
        String batchNo,
        String lcNo,
        String beneficiary,
        BigDecimal drawAmount,
        String status,
        Instant presentedAt,
        Instant deadlineAt,
        Instant refusedAt,
        Instant acceptedAt,
        Instant paidAt,
        List<DocumentItem> documents,
        List<DiscrepancyView> discrepancies) {

    /**
     * 不符点视图，含放弃标记。
     */
    public record DiscrepancyView(String docType, String description, boolean waived) {
    }
}
