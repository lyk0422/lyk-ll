package com.example.starter.api.dto;

/**
 * 采集批次上传结果。
 *
 * @param received        本批包数
 * @param inserted        新接收包数（同内容重放不计）
 * @param replayed        同会话同序号同内容重放包数（未追加记录）
 * @param consumed        本批触发前缀入账包数
 * @param late            本批记迟到包数（归档后）
 * @param watermarkBefore 处理前水位线
 * @param watermarkAfter  处理后水位线
 * @param versionAfter    处理后会话版本号
 * @param cardStatus      处理后工序卡状态
 * @param quarantined     本批入账是否触发整批隔离评审
 */
public record AcquisitionBatchResult(
        long batchId,
        String sessionNo,
        int received,
        int inserted,
        int replayed,
        int consumed,
        int late,
        int watermarkBefore,
        int watermarkAfter,
        int versionAfter,
        String cardStatus,
        boolean quarantined,
        String message) {
}
