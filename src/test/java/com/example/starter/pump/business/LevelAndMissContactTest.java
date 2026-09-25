package com.example.starter.pump.business;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 液位复测：站点＋时刻去重、恰满30分钟失联派单、归档后迟到只记迟到事件。
 */
class LevelAndMissContactTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    private Long start(String reqId) {
        return facade.handleWarning(new WarningRequest(reqId, "ZN", "ORANGE", T0)).pumpdownId();
    }

    @Test
    void stationAndReadingTime_isDeduplicated() {
        start("w-dedup");
        var first = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(2), new BigDecimal("3.000")));
        assertThat(first.accepted()).isTrue();
        assertThat(first.deduplicated()).isFalse();

        var dup = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(2), new BigDecimal("9.999")));
        assertThat(dup.deduplicated()).isTrue();
        assertThat(dup.readingId()).isEqualTo(first.readingId());
        // 去重不重复落库
        assertThat(count("level_reading")).isEqualTo(1);
    }

    @Test
    void missContact_exactlyThirtyMinutes_dispatchesOrder_lessDoesNot() {
        Long id = start("w-miss");
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-start", id, "PS-01", "START", T0, null));
        facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(2), new BigDecimal("3.000")));

        // 末次数据 +29 分钟：不足 30 分钟，不判失联
        var before = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(31), new BigDecimal("3.000")));
        assertThat(before.missContactOrderId()).isNull();

        // 恰满 30 分钟（相对末次 t=31，恰满为 t=61）：判定失联派单
        var atThreshold = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(61), new BigDecimal("3.000")));
        assertThat(atThreshold.missContactOrderId()).isNotNull();
        assertThat(atThreshold.missContactTriggeredAt()).isEqualTo(T0.plusMinutes(61));
        assertThat(count("miss_contact_order")).isEqualTo(1);

        // 之后数据不再重复派单（一站一事件一工单，唯一约束）
        var later = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(100), new BigDecimal("3.000")));
        assertThat(later.missContactOrderId()).isEqualTo(atThreshold.missContactOrderId());
        assertThat(count("miss_contact_order")).isEqualTo(1);
    }

    @Test
    void lateReadingAfterArchive_onlyRecordedAsLateEvent() {
        Long id = start("w-late");
        facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(1), new BigDecimal("3.000")));
        facade.handleArchive(new ArchiveRequest("a-late", id, T0.plusHours(2)));

        // 归档后上送的液位：接收时刻推进到归档之后，不进曲线只记迟到事件
        clock.setNow(T0.plusHours(3));
        var late = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusHours(3), new BigDecimal("2.000")));
        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isTrue();
        assertThat(count("late_reading_event")).isEqualTo(1);

        // 曲线仍只有归档前的 1 条，迟到数据不参与复算
        var replay = facade.replay(id);
        assertThat(replay.levelCurve()).hasSize(1);
        assertThat(replay.status()).isEqualTo("ARCHIVED");

        // 迟到事件同样按站点＋时刻去重
        var lateDup = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusHours(3), new BigDecimal("2.000")));
        assertThat(lateDup.deduplicated()).isTrue();
        assertThat(count("late_reading_event")).isEqualTo(1);
    }
}
