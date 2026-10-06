package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 干扰投诉登记。
 *
 * @param id                主键
 * @param requestId         幂等键
 * @param stationCode       监测站编号
 * @param frequencyMhz      实测频率，单位 MHz
 * @param sampledAt         采样时刻（UTC）
 * @param fieldStrengthDbuv 实测场强，单位 dBuV
 * @param evidenceId        合成证据标识
 * @param fingerprint       全部业务字段指纹
 * @param triggeredEventId  本投诉触发的干扰事件主键；null=未触发
 * @param createdAt         登记时刻（UTC）
 */
public record Complaint(long id, String requestId, String stationCode, BigDecimal frequencyMhz,
                        LocalDateTime sampledAt, BigDecimal fieldStrengthDbuv, String evidenceId,
                        String fingerprint, Long triggeredEventId, LocalDateTime createdAt) {
}
