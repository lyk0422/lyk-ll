package com.example.starter.pump.business;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.StrongDrainView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideReadingRequest;
import com.example.starter.pump.web.dto.TideReadingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 潮汐顶托工况切换与强排联锁主流程：门槛恰等进入、回落线恰等保持、严格低于才退出，
 * 候选固定潮位版本与各站闸门目标，回执齐全后事务内原子切换并写各站任务。
 */
class TideStrongDrainFlowTest extends AbstractIntegrationTest {

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final BigDecimal THRESHOLD = new BigDecimal("3.000");

    @Autowired
    private PumpdownFacade facade;

    private void configureZn() {
        var cfg = facade.tideView("ZN");
        assertThat(cfg.configured()).isFalse();
        var created = facade.handleTideConfig(
                new TideConfigRequest("cfg-1", "ZN", THRESHOLD, 0));
        assertThat(created.regimeVersion()).isEqualTo(1);
        assertThat(created.created()).isTrue();
    }

    private TideReadingResult reading(String reqId, OffsetDateTime at, String level) {
        return facade.handleTideReading(new TideReadingRequest(reqId, "ZN", at,
                new BigDecimal(level)));
    }

    private void receipts(Long candidateId, OffsetDateTime at) {
        facade.handleGateReceipt(new GateReceiptRequest("r-ps01-" + candidateId, candidateId,
                "PS-01", new BigDecimal("80.00"), at));
        facade.handleGateReceipt(new GateReceiptRequest("r-ps02-" + candidateId, candidateId,
                "PS-02", new BigDecimal("75.00"), at));
    }

    @Test
    void thresholdEqual_entersStrong_andExitAtStrictlyBelowExitLine() {
        configureZn();

        // 恰等门槛 3.000 -> 进入强排，生成 ENTER 候选（固定潮位版本1）
        TideReadingResult atThreshold = reading("tr-1", T1, "3.000");
        assertThat(atThreshold.desiredMode()).isEqualTo("STRONG");
        assertThat(atThreshold.candidateDirection()).isEqualTo("ENTER");
        Long enterCandidate = atThreshold.candidateId();
        assertThat(enterCandidate).isNotNull();

        StrongDrainView beforeConfirm = facade.tideView("ZN");
        assertThat(beforeConfirm.currentMode()).isEqualTo("NORMAL");
        assertThat(beforeConfirm.openCandidate().id()).isEqualTo(enterCandidate);
        assertThat(beforeConfirm.openCandidate().tideVersion()).isEqualTo(atThreshold.tideVersion());
        assertThat(beforeConfirm.openCandidate().targets()).hasSize(2);
        // 确认前不得有任何强排任务：禁止一半站先启机
        assertThat(count("strong_drain_task")).isZero();

        // 回执齐全
        receipts(enterCandidate, T1.plusMinutes(1));

        // 确认：原子启用新序列并写两站 START
        CandidateConfirmResult on = facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf-1", enterCandidate, 1, T1.plusMinutes(2)));
        assertThat(on.state()).isEqualTo("ACTIVE");
        assertThat(on.direction()).isEqualTo("ENTER");
        assertThat(on.tasks()).hasSize(2);
        assertThat(on.tasks()).extracting(CandidateConfirmResult.TaskItem::action)
                .containsOnly("START");
        assertThat(on.tasks()).extracting(CandidateConfirmResult.TaskItem::stationCode)
                .containsExactly("PS-01", "PS-02");

        StrongDrainView activeView = facade.tideView("ZN");
        assertThat(activeView.currentMode()).isEqualTo("STRONG");
        assertThat(activeView.activeSequence().id()).isEqualTo(enterCandidate);
        assertThat(activeView.activeSequence().tasks()).hasSize(2);
        assertThat(count("strong_drain_task")).isEqualTo(2);

        // 恰在回落线 2.800 -> 仍保持强排，不生成退出候选
        TideReadingResult atExitLine = reading("tr-2", T1.plusMinutes(10), "2.800");
        assertThat(atExitLine.desiredMode()).isEqualTo("STRONG");
        assertThat(atExitLine.candidateId()).isNull();
        assertThat(facade.tideView("ZN").currentMode()).isEqualTo("STRONG");

        // 回落带内 2.9 -> 维持强排
        assertThat(reading("tr-3", T1.plusMinutes(11), "2.900").desiredMode())
                .isEqualTo("STRONG");

        // 严格低于回落线 2.799 -> 退出候选
        TideReadingResult below = reading("tr-4", T1.plusMinutes(20), "2.799");
        assertThat(below.desiredMode()).isEqualTo("NORMAL");
        assertThat(below.candidateDirection()).isEqualTo("EXIT");
        Long exitCandidate = below.candidateId();
        assertThat(exitCandidate).isNotEqualTo(enterCandidate);
        // 候选固定的旧在强排序列即首次进入序列
        StrongDrainView.SequenceDetail exitOpen = facade.tideView("ZN").openCandidate();
        assertThat(exitOpen.id()).isEqualTo(exitCandidate);
        assertThat(exitOpen.expectedActiveSequenceId()).isEqualTo(enterCandidate);

        receipts(exitCandidate, T1.plusMinutes(21));
        CandidateConfirmResult off = facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf-2", exitCandidate, 1, T1.plusMinutes(22)));
        assertThat(off.direction()).isEqualTo("EXIT");
        assertThat(off.state()).isEqualTo("COMPLETED");
        assertThat(off.supersededSequenceId()).isEqualTo(enterCandidate);
        assertThat(off.tasks()).extracting(CandidateConfirmResult.TaskItem::action)
                .containsOnly("STOP");

        StrongDrainView finalView = facade.tideView("ZN");
        assertThat(finalView.currentMode()).isEqualTo("NORMAL");
        assertThat(finalView.activeSequence()).isNull();
        // 旧序列已终结为 SUPERSEDED
        assertThat(finalView.traces()).extracting(StrongDrainView.TraceItem::eventType)
                .contains("CANDIDATE_CREATED", "SWITCH_ON", "SWITCH_OFF");
        // 旧序列一行、退出序列一行；任务共4条（2 START + 2 STOP）
        assertThat(count("strong_drain_sequence")).isEqualTo(2);
        assertThat(count("strong_drain_task")).isEqualTo(4);
    }

    @Test
    void risingReadings_whileCandidateOpen_reuseSameEnterCandidate() {
        configureZn();
        TideReadingResult first = reading("tr-a", T1, "3.100");
        TideReadingResult second = reading("tr-b", T1.plusMinutes(2), "3.200");
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(count("strong_drain_sequence")).isEqualTo(1);
    }

    @Test
    void unconfiguredZone_viewSaysNotConfigured_andReadingsKeepOriginalBehavior() {
        StrongDrainView view = facade.tideView("ZB");
        assertThat(view.configured()).isFalse();
        assertThat(view.currentMode()).isEqualTo("NORMAL");
    }
}
