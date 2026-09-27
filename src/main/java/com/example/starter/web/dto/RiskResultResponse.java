package com.example.starter.web.dto;

/**
 * 逐只抽检结果提交响应。
 *
 * @param batchNo          屠宰批次号
 * @param version          变更后风险版本
 * @param sequenceNo       结果所属清单序号
 * @param headNo           头只编号
 * @param result           受理结果：NEGATIVE 阴性 / POSITIVE 阳性
 * @param duplicated       是否同结果重复提交（无新记录，原样重放语义外的幂等受理）
 * @param checklistCompleted 当前清单是否已全部出结果
 * @param samplingCompleted 全部清单是否已完成
 * @param allNegative      是否全部样本阴性
 * @param quarantineActive 是否进入（或已处于）专项隔离
 * @param late             是否核销后迟到受理：TRUE 只记迟到告警，不追改冻结出厂证据
 */
public record RiskResultResponse(
        String batchNo,
        int version,
        long sequenceNo,
        String headNo,
        String result,
        boolean duplicated,
        boolean checklistCompleted,
        boolean samplingCompleted,
        boolean allNegative,
        boolean quarantineActive,
        boolean late) {
}
