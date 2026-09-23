package com.example.starter.trajectory.domain;

import java.util.List;

/**
 * 轨迹安全校验结果。violations 已按规则编号、首次触发时间、对象 ID 固定排序，
 * resultHash 对相同输入与规则版本稳定，重复执行得到相同摘要与哈希。
 *
 * @param requestId   幂等键
 * @param ruleVersion 使用的规则版本
 * @param safe        是否安全（无任何违规）
 * @param violations  有序违规证据列表
 * @param summary     摘要文本
 * @param resultHash  稳定结果哈希（SHA-256 十六进制）
 */
public record ValidationResult(
        String requestId,
        String ruleVersion,
        boolean safe,
        List<Violation> violations,
        String summary,
        String resultHash
) {
}
