package com.example.starter.web.dto;

import java.util.List;

/**
 * 批次全量视图：头只台账、判定明细、回执勾稽与迟到事件，均可由明细复算。
 *
 * @param batchNo          屠宰批次号
 * @param status           批次状态
 * @param species          畜种
 * @param farmOrigin       养殖场来源
 * @param declaredAt       最近一次申报时间（UTC ISO-8601）
 * @param preQuarantinedAt 宰前检疫通过时间，NULL 表示未检疫
 * @param judgedAt         判定完成时间，NULL 表示未判定
 * @param releasedAt       核销时间，NULL 表示未核销
 * @param heads            头只台账
 * @param judgments        判定明细
 * @param receipts         回执列表
 * @param lateEvents       迟到事件
 * @param reconciliation   回执勾稽结果
 */
public record BatchView(
        String batchNo,
        String status,
        String species,
        String farmOrigin,
        String declaredAt,
        String preQuarantinedAt,
        String judgedAt,
        String releasedAt,
        List<HeadView> heads,
        List<JudgmentView> judgments,
        List<ReceiptView> receipts,
        List<LateEventView> lateEvents,
        List<ReconciliationEntry> reconciliation) {

    /**
     * 头只台账视图。
     *
     * @param headNo           头只编号
     * @param status           头只状态
     * @param inspectionResult 同步检验结果
     * @param judgmentCategory 判定类别
     * @param highTemp         是否标注高温处理
     * @param circulationArea  限定流通区域
     * @param inventoryMarked  库存标识
     */
    public record HeadView(
            String headNo,
            String status,
            String inspectionResult,
            String judgmentCategory,
            Boolean highTemp,
            String circulationArea,
            boolean inventoryMarked) {
    }

    /**
     * 判定明细视图。
     *
     * @param headNo          头只编号
     * @param category        判定类别
     * @param highTemp        是否标注高温处理
     * @param circulationArea 限定流通区域
     */
    public record JudgmentView(
            String headNo,
            String category,
            boolean highTemp,
            String circulationArea) {
    }

    /**
     * 回执视图。
     *
     * @param receiptNo  回执编号
     * @param category   回执类别
     * @param headCount  回执头只数
     * @param processor  处理单位
     * @param late       是否迟到回执
     * @param receivedAt 登记时间（UTC ISO-8601）
     */
    public record ReceiptView(
            String receiptNo,
            String category,
            int headCount,
            String processor,
            boolean late,
            String receivedAt) {
    }

    /**
     * 迟到事件视图。
     *
     * @param eventType 事件类型
     * @param detail    事件明细
     * @param createdAt 记录时间（UTC ISO-8601）
     */
    public record LateEventView(
            String eventType,
            String detail,
            String createdAt) {
    }
}
