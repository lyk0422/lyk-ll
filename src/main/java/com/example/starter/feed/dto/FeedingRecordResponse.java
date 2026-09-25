package com.example.starter.feed.dto;

import java.time.Instant;

/**
 * 含药饲喂登记响应。
 *
 * @param batchNo     批次号
 * @param formulaCode 配方编码
 * @param version     配方版本
 * @param feedAtUtc   饲喂时刻（UTC）
 * @param late        是否为出栏后迟到事件
 * @param alerted     是否因此产生合规告警
 */
public record FeedingRecordResponse(
        String batchNo,
        String formulaCode,
        int version,
        Instant feedAtUtc,
        boolean late,
        boolean alerted
) {
}
