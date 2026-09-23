package com.example.starter.trajectory.dto;

import java.util.List;
import java.util.Map;

/**
 * 轨迹安全校验结果。失败项按规则编号、首次触发时间、对象 ID 固定排序；
 * {@code resultHash} 为失败项与摘要的确定性 SHA-256，重复执行得到相同值。
 *
 * @param requestId      幂等键
 * @param ruleVersion    复核所用规则版本
 * @param safe           是否无任何失败项
 * @param failureCount   失败项总数
 * @param failuresByRule 各规则失败项数量，键为规则编号标识
 * @param resultHash     结果内容的稳定哈希（64 位十六进制 SHA-256）
 * @param failures       排序后的失败项列表
 */
public record ValidationResult(
        String requestId,
        String ruleVersion,
        boolean safe,
        int failureCount,
        Map<String, Integer> failuresByRule,
        String resultHash,
        List<FailureItem> failures) {
}
