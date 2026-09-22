package com.example.starter.bag.web.dto;

/**
 * 单个分段的恢复结果。
 *
 * @param topic              Topic 名称
 * @param segIndex           分段序号
 * @param keptCount          保留的可验证完整前缀消息数
 * @param quarantinedCount   被隔离的不完整消息数
 * @param rebuiltIndexCount  重建的索引行数
 * @param prefixVerified     完整前缀链校验是否与段级校验一致
 * @param finalStatus        恢复后段状态
 */
public record SegmentRecoveryReport(
        String topic,
        int segIndex,
        long keptCount,
        long quarantinedCount,
        long rebuiltIndexCount,
        boolean prefixVerified,
        String finalStatus) {
}
