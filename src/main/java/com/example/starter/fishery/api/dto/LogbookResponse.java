package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 渔捞日志受理结果；{@code reviewOpened=true} 表示本次日志触发兼捕审查。
 */
public record LogbookResponse(
        Long logId,
        String tripCode,
        String zoneCode,
        LocalDate logDate,
        Instant fishingAt,
        int seqNo,
        BigDecimal totalKg,
        boolean bycatchFlag,
        boolean late,
        boolean reviewOpened,
        List<WeightLineView> lines,
        boolean replayed) implements ReplayAware {

    @Override
    public LogbookResponse withReplayed(boolean replayed) {
        return new LogbookResponse(logId, tripCode, zoneCode, logDate, fishingAt, seqNo, totalKg,
                bycatchFlag, late, reviewOpened, lines, replayed);
    }
}
