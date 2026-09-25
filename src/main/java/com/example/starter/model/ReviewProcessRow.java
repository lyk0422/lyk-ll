package com.example.starter.model;

import com.example.starter.domain.ProcessStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 审查流程行（review_process 联表 vaccine_batch）。
 *
 * @param processId          流程ID
 * @param batchId            批次ID
 * @param batchNo            批号
 * @param enterprise         生产企业
 * @param productName        品种名称
 * @param status             流程状态
 * @param missingItemsJson   一次性告知缺项清单JSON，无缺项为null
 * @param correctionDeadline 补正截止日（UTC），无需补正为null
 * @param evaluationResult   综合评定结论，未评定为null
 * @param failReason         不合格原因，合格为null
 * @param createdAt          创建时间（UTC）
 */
public record ReviewProcessRow(
        long processId,
        long batchId,
        String batchNo,
        String enterprise,
        String productName,
        ProcessStatus status,
        String missingItemsJson,
        LocalDate correctionDeadline,
        String evaluationResult,
        String failReason,
        Instant createdAt) {
}
