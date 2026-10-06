package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 干扰事件。
 *
 * @param id                    主键
 * @param frequencyMhz          受扰频率，单位 MHz
 * @param transmitterId         关联发射机主键
 * @param status                状态：OPEN/CONFIRMED/CLOSED
 * @param conclusion            初查结论：CONFIRMED/CLEARED；null=未初查
 * @param confirmedLimitW       确认时限值快照，单位 W；null=未确认
 * @param triggerComplaintId    触发投诉主键
 * @param createdAt             创建时刻（UTC）
 * @param concludedAt           初查时刻（UTC）；null=未初查
 * @param closedAt              结案时刻（UTC）；null=未结案
 * @param investigateRequestId  初查幂等键；null=未初查
 * @param investigateFingerprint 初查请求指纹
 */
public record InterferenceEvent(long id, BigDecimal frequencyMhz, long transmitterId, String status,
                                String conclusion, Integer confirmedLimitW, long triggerComplaintId,
                                LocalDateTime createdAt, LocalDateTime concludedAt, LocalDateTime closedAt,
                                String investigateRequestId, String investigateFingerprint) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String CONCLUSION_CONFIRMED = "CONFIRMED";
    public static final String CONCLUSION_CLEARED = "CLEARED";
}
