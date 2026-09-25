package com.example.starter.roaming.web.dto;

import java.time.OffsetDateTime;

/**
 * 计量快照上传请求。
 *
 * @param requestId  幂等键
 * @param sessionNo  会话号
 * @param meterSeq   抄表序号（从 1 递增）
 * @param meterWh    电表累计读数，单位 Wh
 * @param snapshotAt 快照时刻（UTC）
 */
public record MeterRequest(
        String requestId,
        String sessionNo,
        Integer meterSeq,
        Long meterWh,
        OffsetDateTime snapshotAt) {
}
