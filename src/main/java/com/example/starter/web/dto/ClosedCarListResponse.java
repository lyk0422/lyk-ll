package com.example.starter.web.dto;

import java.util.List;

/**
 * 关门车清单复算响应。
 *
 * @param jobId         作业ID
 * @param closedCarNos  关门车车辆号清单
 * @param closedCount   关门车辆数
 * @param totalCount    在列辆数
 * @param ratio         关门车比例（0~1）
 * @param withinLimit   是否不超过 6% 上限（恰 6% 合法）
 */
public record ClosedCarListResponse(
        long jobId,
        List<String> closedCarNos,
        int closedCount,
        int totalCount,
        double ratio,
        boolean withinLimit) {
}
