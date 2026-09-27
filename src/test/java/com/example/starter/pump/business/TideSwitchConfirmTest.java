package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfirmRequest;
import com.example.starter.pump.web.dto.TideConfirmResult;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 强排切换候选确认：事务内校验版本、占用与闸门回执，全部到位才原子切换；
 * 任一不到位零切换，不允许一半站先启机；10分钟间隔、目标停泵、安全下限不放宽。
 */
class TideSwitchConfirmTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);
    private static final Instant U1 = Instant.parse("2026-09-26T00:01:00Z");
    private static final Instant U2 = Instant.parse("2026-09-26T00:02:00Z");
    private static final Instant U3 = Instant.parse("2026-09-26T00:03:00Z");

    @Autowired
    private PumpdownFacade facade;

    /** 配置分区潮汐控制（版本1）并启动预降事件。 */
    private Long setupZoneWithEvent() {
        facade.handleTideConfig(new TideConfigRequest("tc-c", "ZN", new BigDecimal("5.000"), null));
        return facade.handleWarning(new WarningRequest("w-c", "ZN", "ORANGE", T0)).pumpdownId();
    }

    /** 上送恰达门槛潮位，生成进入强排候选。 */
    private Long enterCandidate() {
        return facade.handleTideLevel(
                new TideLevelRequest("tl-c", "ZN", new BigDecimal("5.000"), U1, 1)).candidateId();
    }

    private TideConfirmRequest confirmEnter(String requestId, Long candidateId, int version,
                                            LocalDateTime at) {
        return new TideConfirmRequest(requestId, candidateId, version, at, List.of(
                new TideConfirmRequest.GateReceipt("PS-01", new BigDecimal("80.00")),
                new TideConfirmRequest.GateReceipt("PS-02", new BigDecimal("75.00"))));
    }

    @Test
    void confirm_enterForced_activatesAtomically() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();
        LocalDateTime t1 = T0.plusMinutes(30);

        TideConfirmResult result = facade.handleTideConfirm(
                confirmEnter("cf-1", candidateId, 1, t1));

        assertThat(result.targetMode()).isEqualTo("FORCED");
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.taskIds()).hasSize(2);
        assertThat(result.replayed()).isFalse();

        // 原子启用：两站任务 + 闸门日志同事务写入
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(count("gate_opening_log")).isEqualTo(2);
        assertThat(count("pump_sequence")).isEqualTo(1);
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("ACTIVATED");
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("FORCED");
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(2);

        var trail = facade.tideTrail("ZN");
        assertThat(trail.items()).hasSize(1);
        assertThat(trail.items().get(0).fromMode()).isEqualTo("NORMAL");
        assertThat(trail.items().get(0).toMode()).isEqualTo("FORCED");
        assertThat(trail.items().get(0).version()).isEqualTo(2);
        assertThat(trail.items().get(0).candidateId()).isEqualTo(candidateId);
    }

    @Test
    void confirm_missingGateReceipt_zeroSwitch_andFailureDoesNotHoldKey() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();

        // 缺 PS-02 回执：任一不到位零切换
        assertThatThrownBy(() -> facade.handleTideConfirm(new TideConfirmRequest(
                "cf-2", candidateId, 1, T0.plusMinutes(30),
                List.of(new TideConfirmRequest.GateReceipt("PS-01", new BigDecimal("80.00"))))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("GATE_RECEIPT_INCOMPLETE");
                });

        // 零切换：无任务、无序列、候选仍待确认、版本与工况不变
        assertThat(count("pump_task")).isZero();
        assertThat(count("pump_sequence")).isZero();
        assertThat(count("gate_opening_log")).isZero();
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("PENDING");
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("NORMAL");
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(1);

        // 失败不占键：同 requestId 补齐回执后可重新受理
        TideConfirmResult retried = facade.handleTideConfirm(
                confirmEnter("cf-2", candidateId, 1, T0.plusMinutes(30)));
        assertThat(retried.version()).isEqualTo(2);
        assertThat(count("pump_task")).isEqualTo(2);
    }

    @Test
    void confirm_openingMismatchWithPinnedTarget_zeroSwitch() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();

        assertThatThrownBy(() -> facade.handleTideConfirm(new TideConfirmRequest(
                "cf-3", candidateId, 1, T0.plusMinutes(30),
                List.of(new TideConfirmRequest.GateReceipt("PS-01", new BigDecimal("70.00")),
                        new TideConfirmRequest.GateReceipt("PS-02", new BigDecimal("75.00"))))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("GATE_RECEIPT_INCOMPLETE");
                });
        assertThat(count("pump_task")).isZero();
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("PENDING");
    }

    @Test
    void confirm_versionMismatch_rejected409WithCurrentVersion() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();

        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-4", candidateId, 9, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(ex.getCurrentVersion()).isEqualTo(1);
                });
        assertThat(count("pump_task")).isZero();
    }

    @Test
    void confirm_stationOccupiedByRunningPump_zeroSwitch() {
        Long id = setupZoneWithEvent();
        facade.handlePumpCommand(new PumpCommandRequest("m-1", id, "PS-01", "START", T0, null));
        Long candidateId = enterCandidate();

        // PS-01 已在运行被占用：零切换，PS-02 也不得先启机
        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-5", candidateId, 1, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("STATION_OCCUPIED");
                });
        assertThat(count("pump_task")).isEqualTo(1);
        assertThat(count("pump_sequence")).isEqualTo(1);
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("PENDING");
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("NORMAL");
    }

    @Test
    void confirm_startIntervalNotRelaxed_zeroSwitch() {
        Long id = setupZoneWithEvent();
        facade.handlePumpCommand(new PumpCommandRequest("m-2", id, "PS-01", "START", T0, null));
        facade.handlePumpCommand(new PumpCommandRequest("m-3", id, "PS-01", "STOP",
                T0.plusMinutes(5), null));
        Long candidateId = enterCandidate();

        // 距上次启泵仅9分钟：强排不放宽10分钟间隔
        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-6", candidateId, 1, T0.plusMinutes(9))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("START_INTERVAL_TOO_SHORT");
                });
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("PENDING");
    }

    @Test
    void confirm_targetReachedNotRelaxed_zeroSwitch() {
        Long id = setupZoneWithEvent();
        // PS-01 水位恰达预降目标位 2.500：强排不放宽目标停泵
        facade.handleLevel(new LevelIngestRequest("PS-01", T0.plusMinutes(1),
                new BigDecimal("2.500")));
        Long candidateId = enterCandidate();

        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-7", candidateId, 1, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("TARGET_REACHED");
                });
        assertThat(count("pump_task")).isZero();
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("PENDING");
    }

    @Test
    void confirm_belowSafeLowNotRelaxed_zeroSwitch() {
        Long id = setupZoneWithEvent();
        // PS-02 水位 1.200 低于安全下限 1.300：强排不放宽安全下限
        facade.handleLevel(new LevelIngestRequest("PS-02", T0.plusMinutes(1),
                new BigDecimal("1.200")));
        Long candidateId = enterCandidate();

        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-8", candidateId, 1, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("SAFE_LOW_BELOW");
                });
        assertThat(count("pump_task")).isZero();
    }

    @Test
    void confirm_withoutActivePumpdown_rejected422() {
        facade.handleTideConfig(new TideConfigRequest("tc-c", "ZN", new BigDecimal("5.000"), null));
        Long candidateId = enterCandidate();

        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-9", candidateId, 1, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("NO_ACTIVE_PUMPDOWN");
                });
        assertThat(count("pump_task")).isZero();
    }

    @Test
    void confirm_unknownCandidate_returns404() {
        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-10", 999L, 1, T0.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("CANDIDATE_NOT_FOUND");
                });
    }

    @Test
    void exitLineHysteresis_exactLineStaysForced_belowExits_andTerminatesOldSequence() {
        setupZoneWithEvent();
        Long enterId = enterCandidate();
        LocalDateTime t1 = T0.plusMinutes(30);
        facade.handleTideConfirm(confirmEnter("cf-11", enterId, 1, t1));
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("FORCED");

        // 恰在回落线（门槛-0.2）：仍保持强排
        var stay = facade.handleTideLevel(
                new TideLevelRequest("tl-c2", "ZN", new BigDecimal("4.800"), U2, 2));
        assertThat(stay.decision()).isEqualTo("NONE");
        assertThat(stay.candidateId()).isNull();
        assertThat(count("tide_switch_candidate")).isEqualTo(1);

        // 回落到门槛减0.2米以下：退出强排
        var exit = facade.handleTideLevel(
                new TideLevelRequest("tl-c3", "ZN", new BigDecimal("4.799"), U3, 2));
        assertThat(exit.decision()).isEqualTo("EXIT_FORCED");
        Long exitCandidate = exit.candidateId();
        assertThat(exitCandidate).isNotNull();
        var exitView = facade.tideCandidate(exitCandidate);
        assertThat(exitView.targetMode()).isEqualTo("NORMAL");
        assertThat(exitView.stations()).allSatisfy(
                s -> assertThat(s.targetOpening()).isEqualByComparingTo("0.00"));

        // 确认退出：原子终结旧序列、启用新序列、写停泵任务
        LocalDateTime t2 = t1.plusMinutes(30);
        TideConfirmResult exited = facade.handleTideConfirm(new TideConfirmRequest(
                "cf-12", exitCandidate, 2, t2, List.of(
                        new TideConfirmRequest.GateReceipt("PS-01", new BigDecimal("0.00")),
                        new TideConfirmRequest.GateReceipt("PS-02", new BigDecimal("0.00")))));
        assertThat(exited.targetMode()).isEqualTo("NORMAL");
        assertThat(exited.version()).isEqualTo(3);

        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("NORMAL");
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(3);
        // 旧序列已终结，新序列活动
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_sequence WHERE status = 'TERMINATED'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_sequence WHERE status = 'ACTIVE'", Long.class))
                .isEqualTo(1);
        // 工况轨迹两条：进入与退出
        var trail = facade.tideTrail("ZN");
        assertThat(trail.items()).hasSize(2);
        assertThat(trail.items().get(1).fromMode()).isEqualTo("FORCED");
        assertThat(trail.items().get(1).toMode()).isEqualTo("NORMAL");
        assertThat(trail.items().get(1).version()).isEqualTo(3);
        // 停泵任务归属新序列
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_task WHERE action = 'STOP' AND reason = 'TIDE_FORCED_EXIT'",
                Long.class)).isEqualTo(2);
    }

    @Test
    void reconfirmActivatedCandidate_rejected_andNewTasksUntouched() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();
        facade.handleTideConfirm(confirmEnter("cf-13", candidateId, 1, T0.plusMinutes(30)));

        // 旧候选回执只归旧任务：重复确认被拒绝，新任务状态不变
        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-14", candidateId, 2, T0.plusMinutes(60))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("CANDIDATE_NOT_PENDING");
                });
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(count("gate_opening_log")).isEqualTo(2);
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(2);
    }

    @Test
    void confirm_idempotentReplay_andSameKeyDifferentParams409() {
        setupZoneWithEvent();
        Long candidateId = enterCandidate();
        var request = confirmEnter("cf-15", candidateId, 1, T0.plusMinutes(30));
        TideConfirmResult first = facade.handleTideConfirm(request);

        // 同键同参：重放首次冻结结果，不产生新任务
        TideConfirmResult replayed = facade.handleTideConfirm(request);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.sequenceId()).isEqualTo(first.sequenceId());
        assertThat(replayed.taskIds()).isEqualTo(first.taskIds());
        assertThat(count("pump_task")).isEqualTo(2);

        // 同键异参：409
        assertThatThrownBy(() -> facade.handleTideConfirm(
                confirmEnter("cf-15", candidateId, 1, T0.plusMinutes(31))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });
        assertThat(count("pump_task")).isEqualTo(2);
    }
}
