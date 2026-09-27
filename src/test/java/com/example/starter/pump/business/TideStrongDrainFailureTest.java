package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideReadingRequest;
import com.example.starter.pump.web.dto.TideReadingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 潮汐强排失败分支：回执缺失/未到位零切换、站占用、启机10分钟间隔、旧序列版本失配、
 * 迟到较旧潮位只留证据、对象不存在404、参数/业务语义错误码。
 */
class TideStrongDrainFailureTest extends AbstractIntegrationTest {

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private PumpdownFacade facade;

    private void configure(String reqId, String zone, String threshold, int expected) {
        facade.handleTideConfig(new TideConfigRequest(reqId, zone, new BigDecimal(threshold), expected));
    }

    private TideReadingResult reading(String reqId, OffsetDateTime at, String level) {
        return facade.handleTideReading(new TideReadingRequest(reqId, "ZN", at,
                new BigDecimal(level)));
    }

    @Test
    void missingReceipt_confirmZeroSwitch_andTraceRecordsRejection() {
        configure("c1", "ZN", "3.000", 0);
        Long candidate = reading("r1", T1, "3.000").candidateId();
        // 只给 PS-01 回执，PS-02 缺失
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));

        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(2))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("GATE_RECEIPT_MISSING"));

        // 零切换：候选仍在、无在强排序列、无任何强排任务
        var view = facade.tideView("ZN");
        assertThat(view.currentMode()).isEqualTo("NORMAL");
        assertThat(view.openCandidate().id()).isEqualTo(candidate);
        assertThat(count("strong_drain_task")).isZero();
        assertThat(count("strong_drain_sequence")).isEqualTo(1);
    }

    @Test
    void gateOpeningNotMatchingTarget_isRejected_andNeverStored() {
        configure("c1", "ZN", "3.000", 0);
        Long candidate = reading("r1", T1, "3.000").candidateId();

        assertThatThrownBy(() -> facade.handleGateReceipt(new GateReceiptRequest("g1", candidate,
                "PS-01", new BigDecimal("50.00"), T1.plusMinutes(1))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("GATE_NOT_IN_POSITION"));
        // 未到位回执不入台账（业务异常整事务回滚），确认仍因回执缺失而零切换
        assertThat(count("gate_receipt")).isZero();
        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(2))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void stationOccupiedByOriginalPump_blocksEnter() {
        // 先用原预降体系把 PS-01 启泵占用
        Long pumpdown = facade.handleWarning(
                new com.example.starter.pump.web.dto.WarningRequest("w1", "ZN", "ORANGE",
                        T1.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()))
                .pumpdownId();
        facade.handlePumpCommand(new com.example.starter.pump.web.dto.PumpCommandRequest(
                "p1", pumpdown, "PS-01", "START",
                T1.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(), null));

        configure("c1", "ZN", "3.000", 0);
        Long candidate = reading("r1", T1.plusMinutes(5), "3.000").candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(6)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(6)));

        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(7))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STATION_OCCUPIED_ORIGINAL"));
        assertThat(count("strong_drain_task")).isZero();
    }

    @Test
    void startWithinTenMinutes_isRejected_notRelaxedForStrongDrain() {
        configure("c1", "ZN", "3.000", 0);
        // 第一次进入并确认（强排启泵）
        Long c1 = reading("r1", T1, "3.000").candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", c1, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", c1, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(1)));
        facade.handleCandidateConfirm(new CandidateConfirmRequest("cf1", c1, 1, T1.plusMinutes(2)));

        // 退出（回落）-> 再进入，若距上次启泵不足10分钟须拒绝
        Long exit = reading("r2", T1.plusMinutes(3), "2.700").candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g3", exit, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(3)));
        facade.handleGateReceipt(new GateReceiptRequest("g4", exit, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(3)));
        facade.handleCandidateConfirm(new CandidateConfirmRequest("cf2", exit, 1, T1.plusMinutes(4)));

        Long reenter = reading("r3", T1.plusMinutes(5), "3.000").candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g5", reenter, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(5)));
        facade.handleGateReceipt(new GateReceiptRequest("g6", reenter, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(5)));
        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf3", reenter, 1, T1.plusMinutes(6))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("START_INTERVAL_TOO_SHORT"));
    }

    @Test
    void lateOlderTide_onlyRecordsEvidence_andDoesNotRegressMode() {
        configure("c1", "ZN", "3.000", 0);
        reading("r1", T1.plusMinutes(10), "3.000");
        // 迟到的较旧时刻潮位（即使很低）也不得让工况倒退
        TideReadingResult late = reading("r-late", T1, "1.000");
        assertThat(late.late()).isTrue();
        assertThat(late.stored()).isFalse();
        assertThat(late.desiredMode()).isEqualTo("NORMAL"); // 当前最新潮位仍是3.0，证据不改工况
        // 证据表有记录，正式潮位表只有一条
        assertThat(count("late_tide_event")).isEqualTo(1);
        assertThat(count("tide_reading")).isEqualTo(1);
    }

    @Test
    void duplicateTideUtcInstant_isIdempotentByUniqueInstant() {
        configure("c1", "ZN", "3.000", 0);
        TideReadingResult first = reading("r1", T1, "3.000");
        // 同一UTC时刻、不同偏移表达（+08:00 的 08:00 == UTC 00:00）
        TideReadingResult sameInstant = facade.handleTideReading(new TideReadingRequest(
                "r2", "ZN", T1.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                        .toOffsetDateTime(), new BigDecimal("3.000")));
        assertThat(sameInstant.duplicate()).isTrue();
        assertThat(sameInstant.tideVersion()).isEqualTo(first.tideVersion());
        assertThat(count("tide_reading")).isEqualTo(1);
    }

    @Test
    void configVersionMismatch_returns409WithCurrentVersion() {
        configure("c1", "ZN", "3.000", 0);
        assertThatThrownBy(() -> facade.handleTideConfig(
                new TideConfigRequest("c2", "ZN", new BigDecimal("3.500"), 5)))
                .isInstanceOfSatisfying(ApiException.VersionMismatchException.class,
                        ex -> {
                            assertThat(ex.getCode()).isEqualTo("CONFIG_VERSION_MISMATCH");
                            assertThat(ex.getCurrentVersion()).isEqualTo(1);
                        });
        // 成功修改：版本1->2
        var updated = facade.handleTideConfig(
                new TideConfigRequest("c3", "ZN", new BigDecimal("3.500"), 1));
        assertThat(updated.regimeVersion()).isEqualTo(2);
        assertThat(updated.created()).isFalse();
    }

    @Test
    void unknownSequence_is404() {
        configure("c1", "ZN", "3.000", 0);
        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", 99999L, 1, T1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    @Test
    void unknownZone_is404() {
        assertThatThrownBy(() -> facade.handleTideConfig(
                new TideConfigRequest("cx", "NOPE", new BigDecimal("3.000"), 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    @Test
    void readingsOnUnconfiguredZone_is422() {
        assertThatThrownBy(() -> reading("r1", T1, "3.000"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TIDE_CONTROL_NOT_CONFIGURED"));
    }

    @Test
    void levelAtSafeLow_blocksStrongStart_notRelaxed() {
        // 原预降期内 PS-01 最新液位已严格低于安全下限1.200
        facade.handleWarning(new com.example.starter.pump.web.dto.WarningRequest(
                "w1", "ZN", "ORANGE",
                T1.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()));
        facade.handleLevel(new com.example.starter.pump.web.dto.LevelIngestRequest(
                "PS-01", T1.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
                new BigDecimal("1.000")));

        configure("c1", "ZN", "3.000", 0);
        Long candidate = reading("r1", T1.plusMinutes(2), "3.000").candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(3)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(3)));

        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(4))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STRONG_START_BLOCKED_BY_LEVEL"));
        assertThat(count("strong_drain_task")).isZero();
    }
}
