package com.example.starter.roaming.web.dto;

import java.time.Instant;

/**
 * 计量快照上传响应。
 *
 * @param duplicate     会话号＋抄表序号已存在（重复快照不重计电量）
 * @param totalEnergyWh 归位后会话累计电量 Wh
 * @param discrepancyNo 封存后迟到时生成的差异挂账单号，否则为空
 */
public record MeterResponse(
        String requestId,
        boolean accepted,
        boolean duplicate,
        String sessionNo,
        int meterSeq,
        long meterWh,
        Instant snapshotAt,
        int lastMeterSeq,
        long totalEnergyWh,
        String batchNo,
        String batchStatus,
        String discrepancyNo) {
}
