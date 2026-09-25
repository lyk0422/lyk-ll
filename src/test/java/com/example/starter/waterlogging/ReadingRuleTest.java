package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.waterlogging.api.LevelReadingRequest;
import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PlanStatus;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.service.WaterLoggingQueryService;
import com.example.starter.waterlogging.service.WaterLoggingService;
import com.example.starter.waterlogging.web.IdempotentResponse;

/**
 * 液位复测规则：站点+时刻去重；恰等安全下限合法继续运行；严格低于下限立即停泵；
 * 水位曲线可复算。
 */
class ReadingRuleTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private WaterLoggingService service;
    @Autowired
    private WaterLoggingQueryService queryService;

    private WarningStartRequest warning(String requestId, LocalDateTime time, String zone,
                                        BigDecimal target, BigDecimal gate) {
        return new WarningStartRequest(requestId, "RED", zone, "梅岭河", target, time, gate);
    }

    @Test
    void levelExactlyAtSafetyLowKeepsRunningButBelowStopsImmediately() {
        // XIHU 仅一台 XH-01，安全下限 2.40；目标位 2.30（低于下限）。
        service.startOnWarning(warning("WARN-LEVEL-1", T0, "XIHU",
                new BigDecimal("2.30"), new BigDecimal("60.00")));

        // 水位恰等安全下限 2.40：合法，继续运行；且未达目标位，序列不收官。
        var atLow = service.reportReading(new LevelReadingRequest(
                "READ-LOW-1", "XH-01", T0.plusMinutes(10), new BigDecimal("2.40")));
        assertThat(atLow.data().accepted()).isTrue();
        PlanView planAfterEqual = queryService.getPlan("WARN-LEVEL-1");
        assertThat(planAfterEqual.status()).isEqualTo(PlanStatus.DRAINING);
        assertThat(planAfterEqual.stations()).singleElement()
                .satisfies(station -> assertThat(station.runStatus()).isEqualTo(PumpAction.RUNNING));

        // 水位严格低于安全下限 2.39：立即停泵（目标位尚未达成也停）。
        service.reportReading(new LevelReadingRequest(
                "READ-LOW-2", "XH-01", T0.plusMinutes(11), new BigDecimal("2.39")));
        PlanView planAfterBelow = queryService.getPlan("WARN-LEVEL-1");
        assertThat(planAfterBelow.status()).isEqualTo(PlanStatus.DRAINING);
        assertThat(planAfterBelow.stations()).singleElement()
                .satisfies(station -> {
                    assertThat(station.runStatus()).isEqualTo(PumpAction.STOPPED);
                    assertThat(station.gateOpening()).isEqualByComparingTo("0");
                });
        // 安全停机动作入台账。
        assertThat(queryService.getCommands("WARN-LEVEL-1"))
                .anySatisfy(command -> {
                    assertThat(command.action()).isEqualTo("STOP");
                    assertThat(command.commandTime()).isEqualTo(T0.plusMinutes(11));
                });
    }

    @Test
    void readingIsDeduplicatedByStationAndTime() {
        service.startOnWarning(warning("WARN-LEVEL-2", T0, "XIHU",
                new BigDecimal("2.30"), new BigDecimal("60.00")));

        LocalDateTime readingTime = T0.plusMinutes(5);
        var first = service.reportReading(new LevelReadingRequest(
                "READ-DEDUP-1", "XH-01", readingTime, new BigDecimal("3.50")));
        assertThat(first.data().accepted()).isTrue();
        // 同 requestId 重放：返回首次结果。
        var replay = service.reportReading(new LevelReadingRequest(
                "READ-DEDUP-1", "XH-01", readingTime, new BigDecimal("3.50")));
        assertThat(replay.replay()).isTrue();
        // 不同 requestId 但站点+时刻相同：去重接受但不新增，也不重复触发副作用。
        var duplicate = service.reportReading(new LevelReadingRequest(
                "READ-DEDUP-2", "XH-01", readingTime, new BigDecimal("3.50")));
        assertThat(duplicate.data().accepted()).isFalse();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM water_level_reading
                WHERE station_code = 'XH-01' AND reading_time = ?
                """, Integer.class, java.sql.Timestamp.valueOf(readingTime));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void waterLevelCurveCanBeRecomputed() {
        service.startOnWarning(warning("WARN-LEVEL-3", T0, "XIHU",
                new BigDecimal("2.30"), new BigDecimal("60.00")));
        service.reportReading(new LevelReadingRequest(
                "READ-C-1", "XH-01", T0.plusMinutes(5), new BigDecimal("3.80")));
        service.reportReading(new LevelReadingRequest(
                "READ-C-2", "XH-01", T0.plusMinutes(10), new BigDecimal("3.10")));

        assertThat(queryService.getCurve("WARN-LEVEL-3"))
                .extracting(ReadingResult::level)
                .containsExactly(new BigDecimal("3.80"), new BigDecimal("3.10"));
    }
}
