package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.waterlogging.api.ArchiveRequest;
import com.example.starter.waterlogging.api.LevelReadingRequest;
import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PlanStatus;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.service.WaterLoggingQueryService;
import com.example.starter.waterlogging.service.WaterLoggingService;
import com.example.starter.waterlogging.web.IdempotentResponse;

/**
 * 主流程：橙色预警启动预降、恰达目标位停泵、复盘归档终态、归档后迟到数据只记事件。
 */
class PreDrainFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);
    private static final BigDecimal TARGET = new BigDecimal("3.00");
    private static final BigDecimal GATE = new BigDecimal("60.00");

    @Autowired
    private WaterLoggingService service;
    @Autowired
    private WaterLoggingQueryService queryService;

    private WarningStartRequest orangeWarning(String requestId, String zone, LocalDateTime time) {
        return new WarningStartRequest(requestId, "ORANGE", zone, "东湾河", TARGET, time, GATE);
    }

    @Test
    void orangeWarningStartsPreDrainAndOpensStationsAtomically() {
        IdempotentResponse<PlanView> response =
                service.startOnWarning(orangeWarning("WARN-FLOW-1", "DONGWAN", T0));

        assertThat(response.replay()).isFalse();
        PlanView plan = response.data();
        assertThat(plan.status()).isEqualTo(PlanStatus.DRAINING);
        assertThat(plan.warningLevel()).isEqualTo("ORANGE");
        assertThat(plan.startedAt()).isEqualTo(T0);
        // 同分区两站全部联动启机，且闸门已同步到位。
        assertThat(plan.stations()).hasSize(2);
        assertThat(plan.stations()).allSatisfy(station -> {
            assertThat(station.runStatus()).isEqualTo(PumpAction.RUNNING);
            assertThat(station.gateOpening()).isEqualByComparingTo(GATE);
        });

        // 启停序列台账复算：两台 START，均标记闸门已调整。
        assertThat(queryService.getCommands("WARN-FLOW-1"))
                .hasSize(2)
                .allSatisfy(command -> {
                    assertThat(command.action()).isEqualTo("START");
                    assertThat(command.gateAdjusted()).isTrue();
                    assertThat(command.commandTime()).isEqualTo(T0);
                });
        // 分区序列锁持有中。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zone_sequence_lock WHERE basin_zone = 'DONGWAN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void yellowWarningDoesNotStartPreDrain() {
        WarningStartRequest yellow = new WarningStartRequest(
                "WARN-YELLOW", "YELLOW", "DONGWAN", "东湾河", TARGET, T0, GATE);
        assertThatThrownBy(() -> service.startOnWarning(yellow))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_drain_plan", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_command", Integer.class)).isZero();
    }

    @Test
    void exactlyTargetLevelStopsPumpsAndArchivingIsTerminal() {
        service.startOnWarning(orangeWarning("WARN-FLOW-2", "DONGWAN", T0));

        // 第一台站复测恰达目标位 3.00：恰达合法停泵，但序列仍有泵运行，预降继续。
        IdempotentResponse<ReadingResult> first = service.reportReading(
                new LevelReadingRequest("READ-DW01-1", "DW-01", T0.plusMinutes(20), TARGET));
        assertThat(first.data().accepted()).isTrue();
        assertThat(queryService.getPlan("WARN-FLOW-2").status()).isEqualTo(PlanStatus.DRAINING);
        assertThat(queryService.getPlan("WARN-FLOW-2").stations())
                .filteredOn(station -> station.stationCode().equals("DW-01"))
                .singleElement()
                .satisfies(station -> {
                    assertThat(station.runStatus()).isEqualTo(PumpAction.STOPPED);
                    assertThat(station.gateOpening()).isEqualByComparingTo("0");
                });

        // 第二台站也恰达目标位：全部停稳，序列收官、分区锁释放。
        service.reportReading(
                new LevelReadingRequest("READ-DW02-1", "DW-02", T0.plusMinutes(21), TARGET));
        PlanView completed = queryService.getPlan("WARN-FLOW-2");
        assertThat(completed.status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(T0.plusMinutes(21));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zone_sequence_lock", Integer.class)).isZero();

        // 复盘归档成功。
        LocalDateTime archiveTime = T0.plusMinutes(40);
        var archive = service.archive(new ArchiveRequest(
                "ARCHIVE-1", "WARN-FLOW-2", archiveTime, "预降达标，复盘通过"));
        assertThat(archive.replay()).isFalse();
        assertThat(archive.data().status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThat(queryService.getPlan("WARN-FLOW-2").status()).isEqualTo(PlanStatus.ARCHIVED);

        // 终态重复归档：同 requestId 幂等重放。
        var replay = service.archive(new ArchiveRequest(
                "ARCHIVE-1", "WARN-FLOW-2", archiveTime, "预降达标，复盘通过"));
        assertThat(replay.replay()).isTrue();
        // 终态重复归档：新 requestId 同样幂等成功，不产生第二条归档记录。
        var again = service.archive(new ArchiveRequest(
                "ARCHIVE-2", "WARN-FLOW-2", archiveTime.plusMinutes(5), "再次归档请求"));
        assertThat(again.replay()).isFalse();
        assertThat(again.data().archivedAt()).isEqualTo(archiveTime);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_archive", Integer.class)).isEqualTo(1);

        // 归档后迟到液位数据：只登记迟到事件，不进水位曲线。
        var late = service.reportReading(new LevelReadingRequest(
                "READ-LATE-1", "DW-01", archiveTime.plusMinutes(10), new BigDecimal("2.90")));
        assertThat(late.data().late()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM late_level_event WHERE station_code = 'DW-01'", Integer.class))
                .isEqualTo(1);
        List<ReadingResult> curve = queryService.getCurve("WARN-FLOW-2");
        assertThat(curve).extracting(ReadingResult::stationCode)
                .containsExactly("DW-01", "DW-02");
        assertThat(curve).noneSatisfy(reading -> assertThat(reading.level())
                .isEqualByComparingTo("2.90"));
    }
}
