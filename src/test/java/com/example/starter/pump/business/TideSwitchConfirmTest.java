package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.service.TideFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
import com.example.starter.pump.web.dto.TideControlUpdateRequest;
import com.example.starter.pump.web.dto.TideIngestRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 强排切换确认：版本/占用/回执校验全部通过才原子启用新序列、终结旧序列并写各站任务；
 * 任一不到位零切换。10分钟启机间隔、预降目标停泵与安全下限不因强排放宽。
 */
class TideSwitchConfirmTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private TideFacade tideFacade;

    @Autowired
    private PumpdownFacade pumpdownFacade;

    /** 配置潮汐（门槛4.5）并启动橙色预降，返回预降事件ID。 */
    private Long setupControlAndPumpdown() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        return pumpdownFacade.handleWarning(new WarningRequest("w-1", "ZN", "ORANGE", T0))
                .pumpdownId();
    }

    /** 上送恰达门槛的潮位，返回进入强排候选ID。 */
    private Long generateEnterCandidate() {
        return tideFacade.ingestTide(new TideIngestRequest(
                "tr-enter", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500"))).candidateId();
    }

    private void submitAllReceipts(Long candidateId, String ps01Opening, String ps02Opening) {
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-1-" + candidateId, "PS-01", new BigDecimal(ps01Opening)));
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-2-" + candidateId, "PS-02", new BigDecimal(ps02Opening)));
    }

    @Test
    void confirmEnterForced_atomicallyActivatesNewSequenceAndTerminatesOld() {
        Long pumpdownId = setupControlAndPumpdown();
        // 旧序列：PS-01 手动启泵后停泵（满足间隔与占用前提）
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-1", pumpdownId, "PS-01", "START", T0, null));
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-2", pumpdownId, "PS-01", "STOP", T0.plusMinutes(20), null));

        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        var result = tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30)));

        assertThat(result.newVersion()).isEqualTo(2);
        assertThat(result.mode()).isEqualTo("FORCED");
        assertThat(result.taskIds()).hasSize(2);
        // 两站同时刻启泵任务 + 闸门到位日志，各站开度按候选固定目标
        assertThat(count("pump_task")).isEqualTo(4); // 旧2条 + 新2条
        assertThat(count("gate_opening_log")).isEqualTo(3); // 旧1条 + 新2条
        // 旧序列全部终结，新序列生效
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_sequence WHERE status = 'TERMINATED'", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_sequence WHERE status = 'ACTIVE' AND id = ?",
                Long.class, result.sequenceId())).isEqualTo(1L);
        // 工况轨迹：NORMAL→FORCED，版本2
        var trajectory = tideFacade.trajectory("ZN");
        assertThat(trajectory.currentMode()).isEqualTo("FORCED");
        assertThat(trajectory.currentVersion()).isEqualTo(2);
        assertThat(trajectory.switches()).hasSize(1);
        assertThat(trajectory.switches().get(0).fromMode()).isEqualTo("NORMAL");
        assertThat(trajectory.switches().get(0).toMode()).isEqualTo("FORCED");
        // 候选状态翻转，回执归属可查
        var detail = tideFacade.candidateDetail(candidateId);
        assertThat(detail.status()).isEqualTo("CONFIRMED");
        assertThat(detail.receipts()).hasSize(2);
    }

    @Test
    void exitLine_exactlyAtStaysForced_belowExitsAndStopsRunningStations() {
        Long pumpdownId = setupControlAndPumpdown();
        Long enterId = generateEnterCandidate();
        submitAllReceipts(enterId, "80.00", "75.00");
        tideFacade.confirm(enterId, new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30)));

        // 恰在回落线（4.500-0.2=4.300）：仍保持强排，不生成退出候选
        var atLine = tideFacade.ingestTide(new TideIngestRequest(
                "tr-line", "ZN", OffsetDateTime.parse("2026-09-26T00:40:00Z"),
                new BigDecimal("4.300")));
        assertThat(atLine.candidateId()).isNull();
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("FORCED");

        // 低于回落线：生成退出候选，固定目标为关闸0.00
        var exit = tideFacade.ingestTide(new TideIngestRequest(
                "tr-exit", "ZN", OffsetDateTime.parse("2026-09-26T00:45:00Z"),
                new BigDecimal("4.299")));
        assertThat(exit.direction()).isEqualTo("EXIT_FORCED");
        var exitDetail = tideFacade.candidateDetail(exit.candidateId());
        assertThat(exitDetail.pinnedVersion()).isEqualTo(2);
        assertThat(exitDetail.targets()).allSatisfy(
                t -> assertThat(t.targetOpening()).isEqualByComparingTo("0.00"));

        submitAllReceipts(exit.candidateId(), "0.00", "0.00");
        var result = tideFacade.confirm(exit.candidateId(),
                new CandidateConfirmRequest("cf-2", 2, T0.plusMinutes(50)));

        assertThat(result.mode()).isEqualTo("NORMAL");
        assertThat(result.newVersion()).isEqualTo(3);
        // 两站运行中 → 各写一条 STOP
        assertThat(result.taskIds()).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_task WHERE action = 'STOP' AND reason = 'TIDE_FORCED_EXIT'",
                Long.class)).isEqualTo(2L);
        var trajectory = tideFacade.trajectory("ZN");
        assertThat(trajectory.currentMode()).isEqualTo("NORMAL");
        assertThat(trajectory.switches()).hasSize(2);
        assertThat(trajectory.switches().get(1).toMode()).isEqualTo("NORMAL");
        assertThat(pumpdownId).isNotNull();
    }

    @Test
    void confirmWithoutCompleteReceipts_rejects422_andZeroSwitch() {
        setupControlAndPumpdown();
        Long candidateId = generateEnterCandidate();
        // 只收到 PS-01 回执，PS-02 缺失
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-1-" + candidateId, "PS-01", new BigDecimal("80.00")));

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("GATE_RECEIPT_INCOMPLETE");
                });

        // 零切换：无任务、无序列、版本与工况不变、候选仍待确认
        assertThat(count("pump_task")).isZero();
        assertThat(count("pump_sequence")).isZero();
        assertThat(tideFacade.trajectory("ZN").currentVersion()).isEqualTo(1);
        assertThat(tideFacade.candidateDetail(candidateId).status()).isEqualTo("PENDING");
    }

    @Test
    void confirmWithStaleVersion_rejects409WithCurrentVersion_andZeroSwitch() {
        setupControlAndPumpdown();
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");
        // 候选生成后门槛被调整：版本变为2，候选固定版本1过期
        tideFacade.updateThreshold("ZN", new TideControlUpdateRequest("tc-u", new BigDecimal("4.600"), 1));

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(api.getMessage()).contains("当前版本: 2");
                });
        assertThat(count("pump_task")).isZero();
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("NORMAL");
    }

    @Test
    void confirmWithoutActivePumpdown_rejects422() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("NO_ACTIVE_PUMPDOWN");
                });
        assertThat(count("pump_task")).isZero();
    }

    @Test
    void confirmWithOccupiedStation_rejects422_andNoStationStarts() {
        Long pumpdownId = setupControlAndPumpdown();
        // PS-01 已被手动启泵占用（运行中）
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-1", pumpdownId, "PS-01", "START", T0, null));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("STATION_OCCUPIED"));

        // 零切换：不允许一半站先启机，PS-02 也没有新任务
        assertThat(count("pump_task")).isEqualTo(1); // 仅手动那一条
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("NORMAL");
    }

    @Test
    void confirmWithinStartInterval_rejects422_notRelaxedByForcedMode() {
        Long pumpdownId = setupControlAndPumpdown();
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-1", pumpdownId, "PS-01", "START", T0, null));
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-2", pumpdownId, "PS-01", "STOP", T0.plusMinutes(5), null));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        // 距上次启泵仅8分钟（<10）：强排不予放宽
        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(8))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("START_INTERVAL_TOO_SHORT"));
        assertThat(count("pump_task")).isEqualTo(2);
    }

    @Test
    void confirmWhenTargetReached_rejects422_notRelaxedByForcedMode() {
        Long pumpdownId = setupControlAndPumpdown();
        // PS-01 水位恰达预降目标2.500
        pumpdownFacade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(10), new BigDecimal("2.500")));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("TARGET_REACHED"));
        assertThat(count("pump_task")).isZero();
        assertThat(pumpdownId).isNotNull();
    }

    @Test
    void confirmWhenBelowSafeLow_rejects422_notRelaxedByForcedMode() {
        Long pumpdownId = setupControlAndPumpdown();
        // PS-02 水位1.200 低于安全下限1.300
        pumpdownFacade.handleLevel(new LevelIngestRequest(
                "PS-02", T0.plusMinutes(10), new BigDecimal("1.200")));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("SAFE_LOW_VIOLATION"));
        assertThat(count("pump_task")).isZero();
        assertThat(pumpdownId).isNotNull();
    }

    @Test
    void receiptWithMismatchedOpening_orUnknownStation_rejects422() {
        setupControlAndPumpdown();
        Long candidateId = generateEnterCandidate();

        assertThatThrownBy(() -> tideFacade.submitReceipt(candidateId,
                new GateReceiptRequest("gr-x", "PS-01", new BigDecimal("50.00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("GATE_OPENING_MISMATCH"));

        assertThatThrownBy(() -> tideFacade.submitReceipt(candidateId,
                new GateReceiptRequest("gr-y", "PS-03", new BigDecimal("70.00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("STATION_NOT_IN_CANDIDATE"));
        assertThat(count("switch_gate_receipt")).isZero();
    }

    @Test
    void receiptToConfirmedCandidate_rejected_oldReceiptsOnlyBelongToOldCandidate() {
        setupControlAndPumpdown();
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");
        tideFacade.confirm(candidateId, new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30)));

        // 旧候选已确认：迟到回执只能归旧候选，拒绝改投，不影响新工况
        assertThatThrownBy(() -> tideFacade.submitReceipt(candidateId,
                new GateReceiptRequest("gr-late", "PS-01", new BigDecimal("80.00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CANDIDATE_NOT_PENDING"));
        assertThat(count("switch_gate_receipt")).isEqualTo(2);
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("FORCED");
    }

    @Test
    void lateManualCommandAtOldTaskTime_conflicts_andNewTasksUntouched() {
        Long pumpdownId = setupControlAndPumpdown();
        // 旧序列：PS-01 在 T0 启泵、T0+20 停泵
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-1", pumpdownId, "PS-01", "START", T0, null));
        pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-2", pumpdownId, "PS-01", "STOP", T0.plusMinutes(20), null));
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");
        var confirmed = tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30)));

        // 切换后：旧序列时刻的迟到启停指令与既有任务冲突，只能归旧任务
        assertThatThrownBy(() -> pumpdownFacade.handlePumpCommand(new PumpCommandRequest(
                "m-late", pumpdownId, "PS-01", "STOP", T0.plusMinutes(20), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("STATION_TIME_CONFLICT"));

        // 新序列任务状态不被旧指令改变：仍2条 START，无新增 STOP
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_task WHERE sequence_id = ? AND action = 'START'",
                Long.class, confirmed.sequenceId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_task WHERE sequence_id = ? AND action = 'STOP'",
                Long.class, confirmed.sequenceId())).isZero();
    }

    @Test
    void confirmAfterPumpdownArchived_rejects422_archivedNeverReopens() {
        Long pumpdownId = setupControlAndPumpdown();
        Long candidateId = generateEnterCandidate();
        submitAllReceipts(candidateId, "80.00", "75.00");
        pumpdownFacade.handleArchive(new ArchiveRequest("ar-1", pumpdownId, T0.plusHours(2)));

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-1", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("NO_ACTIVE_PUMPDOWN"));
        assertThat(count("pump_task")).isZero();
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("NORMAL");
    }
}
