package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 复电确认链复算视图。
 *
 * @param workOrderId   工单ID
 * @param restored      是否双确认齐全已复电
 * @param confirmations 确认记录（按时间升序）
 * @param loadKw        入账的恢复负荷kW，空表示未入账
 * @param recordedAt    负荷恢复记录时间，空表示未入账
 */
public record RestoreChainView(
        long workOrderId,
        boolean restored,
        List<String> confirmations,
        BigDecimal loadKw,
        LocalDateTime recordedAt) {
}
