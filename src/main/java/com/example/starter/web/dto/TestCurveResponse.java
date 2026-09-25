package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试验曲线复算响应：按时间顺序返回风压读数与试验事件。
 *
 * @param jobId    作业ID
 * @param readings 风压读数点（不含迟到数据）
 * @param tests    试验事件（含作废标记）
 */
public record TestCurveResponse(
        long jobId,
        List<ReadingPoint> readings,
        List<TestPoint> tests) {

    /**
     * 风压读数点。
     *
     * @param pressure   列车管风压，单位 kPa
     * @param recordedAt 采集时间
     */
    public record ReadingPoint(int pressure, LocalDateTime recordedAt) {
    }

    /**
     * 试验事件。
     *
     * @param testType         试验类型
     * @param pressureReduction 减压量，单位 kPa
     * @param result           结论
     * @param consistVersion   编组版本
     * @param voided           是否已作废
     * @param createdAt        记录时间
     */
    public record TestPoint(String testType, int pressureReduction, String result,
                            int consistVersion, boolean voided, LocalDateTime createdAt) {
    }
}
