package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.service.TideFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
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
 * 潮汐新写操作的 requestId 幂等：同键同参重放首次冻结结果，同键异参 409，
 * 业务失败不占键；业务、版本与幂等结果同事务。
 */
class TideIdempotencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private TideFacade tideFacade;

    @Autowired
    private PumpdownFacade pumpdownFacade;

    private Long setupCandidateWithReceipts() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        pumpdownFacade.handleWarning(new WarningRequest("w-1", "ZN", "ORANGE", T0));
        Long candidateId = tideFacade.ingestTide(new TideIngestRequest(
                "tr-enter", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500"))).candidateId();
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-1", "PS-01", new BigDecimal("80.00")));
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-2", "PS-02", new BigDecimal("75.00")));
        return candidateId;
    }

    @Test
    void tideControl_sameKeySameParams_replaysFrozenResult_differentParams409() {
        var req = new TideControlCreateRequest("tc-same", "ZN", new BigDecimal("4.500"));
        var first = tideFacade.createControl(req);
        var replay = tideFacade.createControl(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(count("tide_control")).isEqualTo(1);

        assertThatThrownBy(() -> tideFacade.createControl(
                new TideControlCreateRequest("tc-same", "ZN", new BigDecimal("9.999"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });
        // 异参冲突不改变既有配置
        assertThat(tideFacade.trajectory("ZN").currentVersion()).isEqualTo(1);
    }

    @Test
    void tideIngest_sameKeyReplays_differentParams409WithoutNewReading() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        var req = new TideIngestRequest("tr-same", "ZN",
                OffsetDateTime.parse("2026-09-26T00:05:00Z"), new BigDecimal("4.500"));
        var first = tideFacade.ingestTide(req);
        var replay = tideFacade.ingestTide(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.candidateId()).isEqualTo(first.candidateId());
        assertThat(count("tide_reading")).isEqualTo(1);
        assertThat(count("mode_switch_candidate")).isEqualTo(1);

        assertThatThrownBy(() -> tideFacade.ingestTide(new TideIngestRequest(
                "tr-same", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("1.234"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("IDEMPOTENT_CONFLICT"));
        assertThat(count("tide_reading")).isEqualTo(1);
    }

    @Test
    void confirm_sameKeyReplaysFrozenResult_differentParams409() {
        Long candidateId = setupCandidateWithReceipts();
        var req = new CandidateConfirmRequest("cf-same", 1, T0.plusMinutes(30));
        var first = tideFacade.confirm(candidateId, req);
        var replay = tideFacade.confirm(candidateId, req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.sequenceId()).isEqualTo(first.sequenceId());
        assertThat(replay.taskIds()).containsExactlyElementsOf(first.taskIds());
        assertThat(replay.newVersion()).isEqualTo(2);
        // 重放不产生新任务、版本不再加
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(tideFacade.trajectory("ZN").currentVersion()).isEqualTo(2);

        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-same", 1, T0.plusMinutes(31))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("IDEMPOTENT_CONFLICT"));
    }

    @Test
    void failedConfirm_doesNotOccupyRequestId_andSucceedsAfterReceiptsCompleted() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        pumpdownFacade.handleWarning(new WarningRequest("w-1", "ZN", "ORANGE", T0));
        Long candidateId = tideFacade.ingestTide(new TideIngestRequest(
                "tr-enter", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500"))).candidateId();

        // 回执未齐全：业务失败 422，事务回滚不占键
        assertThatThrownBy(() -> tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-retry", 1, T0.plusMinutes(30))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("GATE_RECEIPT_INCOMPLETE"));
        assertThat(count("idempotent_request")).isEqualTo(3); // 配置+预警+潮位三条成功占位

        // 补齐回执后同一 requestId 可正常受理
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-1", "PS-01", new BigDecimal("80.00")));
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-2", "PS-02", new BigDecimal("75.00")));
        var ok = tideFacade.confirm(candidateId,
                new CandidateConfirmRequest("cf-retry", 1, T0.plusMinutes(30)));
        assertThat(ok.newVersion()).isEqualTo(2);
        assertThat(ok.mode()).isEqualTo("FORCED");
    }

    @Test
    void receipt_sameKeyReplays_duplicateStationRejected() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        Long candidateId = tideFacade.ingestTide(new TideIngestRequest(
                "tr-enter", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500"))).candidateId();

        var req = new GateReceiptRequest("gr-same", "PS-01", new BigDecimal("80.00"));
        var first = tideFacade.submitReceipt(candidateId, req);
        var replay = tideFacade.submitReceipt(candidateId, req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(count("switch_gate_receipt")).isEqualTo(1);

        // 同键异参（开度不同）409
        assertThatThrownBy(() -> tideFacade.submitReceipt(candidateId,
                new GateReceiptRequest("gr-same", "PS-01", new BigDecimal("81.00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("IDEMPOTENT_CONFLICT"));
    }
}
