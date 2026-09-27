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
 * 潮汐强排幂等边界：同键同参重放首次冻结结果（不重复生成候选/不重复写任务）、
 * 同键异参409、业务失败回滚占位不占键（同键补齐后可成功）。
 */
class TideStrongDrainIdempotencyTest extends AbstractIntegrationTest {

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void sameReadingKey_sameParams_replaysFrozenCandidate_withoutCreatingAnother() {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        TideReadingRequest req = new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000"));

        TideReadingResult first = facade.handleTideReading(req);
        assertThat(first.replayed()).isFalse();
        assertThat(first.candidateId()).isNotNull();

        TideReadingResult replay = facade.handleTideReading(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.candidateId()).isEqualTo(first.candidateId());
        assertThat(replay.tideVersion()).isEqualTo(first.tideVersion());
        // 重放不重新执行：潮位仍1条、候选仍1条
        assertThat(count("tide_reading")).isEqualTo(1);
        assertThat(count("strong_drain_sequence")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParams_conflict409_andNoWrite() {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        facade.handleTideReading(new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000")));

        assertThatThrownBy(() -> facade.handleTideReading(
                new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.500"))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
        // 异参不产生第二条潮位
        assertThat(count("tide_reading")).isEqualTo(1);
    }

    @Test
    void failedConfirm_doesNotOccupyKey_sameKeySucceedsAfterReceiptsComplete() {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        Long candidate = facade.handleTideReading(
                new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000"))).candidateId();

        // 回执缺失 -> 422，占位与业务同事务回滚，不占 requestId
        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(2))))
                .isInstanceOf(ApiException.class);
        assertThat(count("idempotent_request")).isEqualTo(2); // cfg + tr（失败cf不留台账）

        // 补齐回执后用同一 requestId 重试成功
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(1)));
        var ok = facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(2)));
        assertThat(ok.state()).isEqualTo("ACTIVE");
        assertThat(count("strong_drain_task")).isEqualTo(2);

        // 再次同键同参：重放首次冻结结果，不重复写任务
        var replay = facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 1, T1.plusMinutes(2)));
        assertThat(replay.replayed()).isTrue();
        assertThat(count("strong_drain_task")).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentVersionOnConfirm_conflict409() {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        Long candidate = facade.handleTideReading(
                new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000"))).candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(1)));
        facade.handleCandidateConfirm(new CandidateConfirmRequest("cf", candidate, 1,
                T1.plusMinutes(2)));

        // 同键但 expectedVersion 改为 2 -> 409（指纹不同）
        assertThatThrownBy(() -> facade.handleCandidateConfirm(
                new CandidateConfirmRequest("cf", candidate, 2, T1.plusMinutes(2))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    @Test
    void configCreateReplay_isFrozen_andChangeKeyConflicts() {
        var first = facade.handleTideConfig(
                new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        var replay = facade.handleTideConfig(
                new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.regimeVersion()).isEqualTo(first.regimeVersion());
        assertThat(count("tide_control_config")).isEqualTo(1);
    }
}
