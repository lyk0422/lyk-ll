package com.example.starter.api.dto;

import java.util.List;

/**
 * 连续水位线视图。
 *
 * @param watermark       连续入账水位线：1..watermark 已连续完整入账
 * @param nextExpectedSeq 下一个期望序号（watermark + 1）
 * @param expectedCount   计划包总数
 * @param complete        水位线是否已推进到计划总数（无缺号）
 * @param bufferedSeqs    窗口内已缓冲但尚未随前缀入账的序号（升序）
 */
public record CollectionWatermarkView(
        String sessionNo,
        String status,
        int version,
        int watermark,
        int nextExpectedSeq,
        int expectedCount,
        boolean complete,
        List<Integer> bufferedSeqs) {
}
