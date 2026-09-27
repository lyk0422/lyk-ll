package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区潮位查询视图：已受理潮位曲线与迟到证据。
 *
 * @param zoneCode   流域分区代码
 * @param readings   已受理潮位，按UTC观测时刻排序
 * @param lateEvents 迟到/归档后潮位证据，不参与工况判定
 */
public record TideLevelView(
        String zoneCode,
        List<Reading> readings,
        List<LateEvent> lateEvents
) {

    /** 已受理潮位点。 */
    public record Reading(Long readingId, Instant observedAt, BigDecimal tideLevel,
                          LocalDateTime receivedAt) {
    }

    /** 迟到潮位证据。 */
    public record LateEvent(Long id, Instant observedAt, BigDecimal tideLevel,
                            LocalDateTime receivedAt, String reason) {
    }
}
