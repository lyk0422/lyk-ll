package com.example.starter.feed.api;

import java.time.Instant;

/**
 * 含药饲喂记录上报结果。
 *
 * @param batchNo     批次号
 * @param late        是否为出栏后迟到记录
 * @param alertRaised 是否产生合规告警
 * @param recordedAt  记录受理时刻（UTC）
 */
public record FeedingRecordView(
        String batchNo,
        boolean late,
        boolean alertRaised,
        Instant recordedAt) {
}
