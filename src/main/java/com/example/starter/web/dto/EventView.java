package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 干扰事件视图，含 24 小时督办标记。
 *
 * @param escalated 恰满 24 小时（UTC）仍未初查时为 true
 */
public record EventView(long id, BigDecimal frequencyMhz, long transmitterId, String status,
                        String conclusion, Integer confirmedLimitW, LocalDateTime createdAt,
                        LocalDateTime concludedAt, LocalDateTime closedAt, boolean escalated) {
}
