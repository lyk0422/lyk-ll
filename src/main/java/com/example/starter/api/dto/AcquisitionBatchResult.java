package com.example.starter.api.dto;

/**
 * 批量采集上传结果。
 *
 * @param accepted    新接受包数（不含同内容重放）
 * @param replayed    同内容重放跳过包数
 * @param committed   本批触发前缀入账包数
 * @param late        归档后迟到登记包数
 * @param watermark   处理后的连续水位线
 * @param version     处理后的会话乐观版本
 * @param quarantined 本批入账是否触发整批隔离评审（此时 HTTP 422，已入账部分保留）
 */
public record AcquisitionBatchResult(
        String sessionNo,
        String batchNo,
        int accepted,
        int replayed,
        int committed,
        int late,
        int watermark,
        long version,
        String cardStatus,
        boolean quarantined) {
}
