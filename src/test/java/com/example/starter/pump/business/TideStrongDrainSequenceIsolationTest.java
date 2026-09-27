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
 * 序列隔离与终态不可重开：切换后旧序列（SUPERSEDED）不再受理回执，不能改变新序列任务状态；
 * 已终结候选/序列不可再次确认。
 */
class TideStrongDrainSequenceIsolationTest extends AbstractIntegrationTest {

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private PumpdownFacade facade;

    private Long enterAndConfirm(String cfgId, String trId, OffsetDateTime at, String threshold) {
        facade.handleTideConfig(new TideConfigRequest(cfgId, "ZN", new BigDecimal(threshold), 0));
        Long candidate = facade.handleTideReading(
                new TideReadingRequest(trId, "ZN", at, new BigDecimal(threshold))).candidateId();
        facade.handleGateReceipt(new GateReceiptRequest(trId + "-g1", candidate, "PS-01",
                new BigDecimal("80.00"), at.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest(trId + "-g2", candidate, "PS-02",
                new BigDecimal("75.00"), at.plusMinutes(1)));
        facade.handleCandidateConfirm(new CandidateConfirmRequest(
                trId + "-cf", candidate, 1, at.plusMinutes(2)));
        return candidate;
    }

    @Test
    void receiptToSupersededSequence_isRejected_andDoesNotTouchNewTasks() {
        Long first = enterAndConfirm("cfg", "tr1", T1, "3.000");

        // 退出（潮位严格低于2.8）
        TideReadingResult exit = facade.handleTideReading(
                new TideReadingRequest("tr2", "ZN", T1.plusMinutes(20), new BigDecimal("2.700")));
        Long exitCandidate = exit.candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("tr2-g1", exitCandidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(21)));
        facade.handleGateReceipt(new GateReceiptRequest("tr2-g2", exitCandidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(21)));
        facade.handleCandidateConfirm(new CandidateConfirmRequest(
                "tr2-cf", exitCandidate, 1, T1.plusMinutes(22)));

        // 旧在强排序列 first 已 SUPERSEDED；再向其补回执必须被拒绝
        assertThatThrownBy(() -> facade.handleGateReceipt(new GateReceiptRequest(
                "late-old", first, "PS-01", new BigDecimal("80.00"), T1.plusMinutes(30))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SEQUENCE_NOT_CANDIDATE"));

        // 新（退出）序列任务状态不受影响：两站 STOP 仍为 ACKED，旧序列任务仍只有 START
        var view = facade.tideView("ZN");
        assertThat(view.currentMode()).isEqualTo("NORMAL");
        Integer oldStarts = jdbc.queryForObject(
                "SELECT COUNT(1) FROM strong_drain_task WHERE sequence_id = ? AND action = 'START'",
                Integer.class, first);
        assertThat(oldStarts).isEqualTo(2);
        Integer newStops = jdbc.queryForObject(
                "SELECT COUNT(1) FROM strong_drain_task WHERE sequence_id = ? AND action = 'STOP'",
                Integer.class, exitCandidate);
        assertThat(newStops).isEqualTo(2);
        // 没有新增回执落到旧序列
        Integer oldReceipts = jdbc.queryForObject(
                "SELECT COUNT(1) FROM gate_receipt WHERE sequence_id = ?", Integer.class, first);
        assertThat(oldReceipts).isEqualTo(2);
    }

    @Test
    void completedCandidate_cannotBeReconfirmed() {
        Long candidate = enterAndConfirm("cfg", "tr1", T1, "3.000");
        // 同序列再次确认（新requestId）：已 ACTIVE，不可重开
        assertThatThrownBy(() -> facade.handleCandidateConfirm(new CandidateConfirmRequest(
                "again", candidate, 1, T1.plusMinutes(40))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CANDIDATE_NOT_OPEN"));
    }
}
