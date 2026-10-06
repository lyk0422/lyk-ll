package com.example.starter.weight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.CloseCaseRequest;
import com.example.starter.weight.api.dto.DecisionRequest;
import com.example.starter.weight.api.dto.DecisionResponse;
import com.example.starter.weight.api.dto.OffsiteWeighRequest;
import com.example.starter.weight.api.dto.OffsiteWeighResponse;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewConcludeRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.domain.OffsiteRules;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.LateOffsiteEventRepository;
import com.example.starter.weight.repo.LedgerRepository;
import com.example.starter.weight.repo.ManualPendingRepository;
import com.example.starter.weight.repo.OffsiteWeighRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.service.EnforcementService;
import com.example.starter.weight.service.OffsiteEnforcementService;

/**
 * 非现场取证接入集成测试（真实 H2）：证据链缺项转人工、校准有效期外拦截、
 * 车牌+时刻窗口去重、幂等重放与 409、自动生成处罚的复检推翻退费、结案后迟到事件。
 */
class OffsiteFlowIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE_4AXLE = "沪A10001";   // AXLE_4，限值 31000kg
    private static final String PLATE_2AXLE = "苏C30003";   // AXLE_2，限值 18000kg
    private static final String STATION = "ST001";
    private static final String LANE = "L01";
    private static final String DEVICE_VALID = "DEV-L01";    // 校准有效期 2026-01-01 ~ 2027-01-01
    private static final String DEVICE_EXPIRED = "DEV-L02";  // 校准有效期至 2026-01-01（已过期）
    private static final String IMAGE = "IMG-SYN-0001";

    @Autowired
    private OffsiteEnforcementService offsiteService;
    @Autowired
    private EnforcementService enforcementService;
    @Autowired
    private OffsiteWeighRepository offsiteWeighRepository;
    @Autowired
    private ManualPendingRepository manualPendingRepository;
    @Autowired
    private LateOffsiteEventRepository lateOffsiteEventRepository;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private DecisionRepository decisionRepository;
    @Autowired
    private LedgerRepository ledgerRepository;

    private OffsiteWeighRequest offsite(String requestId, String plate, Instant time,
                                        String totalKg, String imageRef, String deviceId) {
        return new OffsiteWeighRequest(requestId, plate, LANE, time,
                new BigDecimal(totalKg), imageRef, deviceId);
    }

    @Test
    @DisplayName("证据链完整且超限 30%：自动生成 PENALTY 处罚决定并入台账")
    void completeEvidenceAutoGeneratesPenalty() {
        OffsiteWeighResponse response = offsiteService.ingest(
                offsite("OS-AUTO", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_VALID));

        assertThat(response.status()).isEqualTo("AUTO_DECIDED");
        assertThat(response.pendingReason()).isNull();
        assertThat(response.lateEvent()).isFalse();
        assertThat(response.deduped()).isFalse();
        assertThat(response.caseNo()).isNotNull();

        DecisionResponse decision = response.decision();
        assertThat(decision).isNotNull();
        assertThat(decision.docType()).isEqualTo("PENALTY");
        assertThat(decision.tier()).isEqualTo("FINE_POINTS");
        assertThat(decision.overloadRate()).isEqualByComparingTo("0.30");
        assertThat(decision.fineAmount()).isEqualByComparingTo("2000.00");
        assertThat(decision.points()).isEqualTo(3);
        assertThat(decision.status()).isEqualTo("ACTIVE");

        CaseRow caseRow = caseRepository.findByBizKey(
                PLATE_4AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME);
        assertThat(caseRow.status()).isEqualTo("OPEN");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("缺合成影像标识：不自动生成处罚决定，转人工待补")
    void missingImageGoesToManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(
                offsite("OS-NOIMG", PLATE_4AXLE, WEIGH_TIME, "40300.00", null, DEVICE_VALID));

        assertThat(response.status()).isEqualTo("MANUAL_PENDING");
        assertThat(response.pendingReason()).isEqualTo("MISSING_IMAGE");
        assertThat(response.decision()).isNull();
        assertThat(response.caseNo()).isNull();
        assertThat(manualPendingRepository.count()).isEqualTo(1);
        assertThat(manualPendingRepository.findByOffsiteWeighId(response.recordId()).reason())
                .isEqualTo("MISSING_IMAGE");
        // 缺项不得生成案件与文书
        assertThat(caseRepository.findAll()).isEmpty();
        assertThat(decisionRepository.findByRequestId("AUTO-OS-NOIMG")).isNull();
    }

    @Test
    @DisplayName("缺设备状态记录：不自动生成处罚决定，转人工待补")
    void missingDeviceStatusGoesToManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(
                offsite("OS-NODEV", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, "DEV-UNKNOWN"));

        assertThat(response.status()).isEqualTo("MANUAL_PENDING");
        assertThat(response.pendingReason()).isEqualTo("DEVICE_STATUS_MISSING");
        assertThat(response.decision()).isNull();
        assertThat(manualPendingRepository.count()).isEqualTo(1);
        assertThat(caseRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("设备校准有效期外：数据不得进入证据链，转人工待补")
    void expiredCalibrationGoesToManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(
                offsite("OS-EXPCAL", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_EXPIRED));

        assertThat(response.status()).isEqualTo("MANUAL_PENDING");
        assertThat(response.pendingReason()).isEqualTo("DEVICE_CAL_EXPIRED");
        assertThat(response.decision()).isNull();
        assertThat(manualPendingRepository.count()).isEqualTo(1);
        assertThat(caseRepository.findAll()).isEmpty();
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger", Integer.class);
        assertThat(ledgerRows).isZero();
    }

    @Test
    @DisplayName("窗口内重复接入只保留首次；窗口外正常接入")
    void duplicateWithinWindowKeepsFirstOnly() {
        OffsiteWeighResponse first = offsiteService.ingest(
                offsite("OS-DUP-1", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_VALID));
        assertThat(first.status()).isEqualTo("AUTO_DECIDED");

        // 窗口内（+3 分钟）重复：只保留首次，不产生新记录、新案件、新文书
        OffsiteWeighResponse dup = offsiteService.ingest(offsite(
                "OS-DUP-2", PLATE_4AXLE, WEIGH_TIME.plusSeconds(180), "50000.00", IMAGE, DEVICE_VALID));
        assertThat(dup.deduped()).isTrue();
        assertThat(dup.recordId()).isEqualTo(first.recordId());
        assertThat(dup.caseNo()).isEqualTo(first.caseNo());
        assertThat(offsiteWeighRepository.countByPlate(PLATE_4AXLE)).isEqualTo(1);
        assertThat(caseRepository.findAll()).hasSize(1);
        assertThat(decisionRepository.findByCaseId(
                caseRepository.findAll().get(0).id())).hasSize(1);

        // 窗口外（+6 分钟）为新的过磅事件：正常接入并自动决定
        OffsiteWeighResponse outside = offsiteService.ingest(offsite(
                "OS-DUP-3", PLATE_4AXLE, WEIGH_TIME.plusSeconds(360), "40300.00", IMAGE, DEVICE_VALID));
        assertThat(outside.deduped()).isFalse();
        assertThat(outside.status()).isEqualTo("AUTO_DECIDED");
        assertThat(offsiteWeighRepository.countByPlate(PLATE_4AXLE)).isEqualTo(2);
        assertThat(caseRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("与现场记录按车牌+时刻窗口去重：窗口内非现场数据不新建记录")
    void dedupAgainstOnsiteRecordWithinWindow() {
        enforcementService.submitWeigh(new WeighRequest("W-ONSITE", PLATE_4AXLE, STATION,
                WEIGH_TIME, WeighMode.DYNAMIC, new BigDecimal("40300.00")));

        OffsiteWeighResponse dup = offsiteService.ingest(offsite(
                "OS-VS-ONSITE", PLATE_4AXLE, WEIGH_TIME.plusSeconds(120), "40300.00", IMAGE, DEVICE_VALID));
        assertThat(dup.deduped()).isTrue();
        assertThat(dup.status()).isEqualTo("DUPLICATE");
        assertThat(dup.recordId()).isNull();
        assertThat(dup.decision()).isNull();
        // 窗口内重复数据只保留首次：非现场记录不落库、不生成新案件
        assertThat(offsiteWeighRepository.countByPlate(PLATE_4AXLE)).isZero();
        assertThat(caseRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("幂等：同键同参重放首次结果；同键异参 409 且不产生新文书")
    void idempotentReplayAndConflict() {
        OffsiteWeighResponse first = offsiteService.ingest(
                offsite("OS-IDEM", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_VALID));
        OffsiteWeighResponse replay = offsiteService.ingest(
                offsite("OS-IDEM", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_VALID));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.recordId()).isEqualTo(first.recordId());
        assertThat(replay.decision().decisionId()).isEqualTo(first.decision().decisionId());

        assertThatThrownBy(() -> offsiteService.ingest(
                offsite("OS-IDEM", PLATE_4AXLE, WEIGH_TIME, "50000.00", IMAGE, DEVICE_VALID)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.status()).isEqualTo(409);
                    assertThat(ae.code()).isEqualTo("IDEMPOTENT_PARAM_CONFLICT");
                });
        // 同键异参不产生新记录与新文书
        assertThat(offsiteWeighRepository.countByPlate(PLATE_4AXLE)).isEqualTo(1);
        CaseRow caseRow = caseRepository.findByBizKey(
                PLATE_4AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME);
        assertThat(decisionRepository.findByCaseId(caseRow.id())).hasSize(1);
    }

    @Test
    @DisplayName("结案后迟到的非现场数据：只记迟到事件，不改已结案件")
    void lateOffsiteAfterCloseOnlyRecordsEvent() {
        enforcementService.submitWeigh(new WeighRequest("W-LATE-OS", PLATE_4AXLE, STATION,
                WEIGH_TIME, WeighMode.DYNAMIC, new BigDecimal("40300.00")));
        DecisionResponse decision = enforcementService.issueDecision(
                new DecisionRequest("D-LATE-OS", PLATE_4AXLE, STATION, WEIGH_TIME));
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        enforcementService.closeCase(new CloseCaseRequest("C-LATE-OS", PLATE_4AXLE, STATION, WEIGH_TIME));

        OffsiteWeighResponse late = offsiteService.ingest(offsite(
                "OS-LATE", PLATE_4AXLE, WEIGH_TIME.plusSeconds(60), "50000.00", IMAGE, DEVICE_VALID));
        assertThat(late.status()).isEqualTo("LATE_EVENT");
        assertThat(late.lateEvent()).isTrue();
        assertThat(late.decision()).isNull();
        assertThat(late.caseNo()).isEqualTo(caseRow.caseNo());
        assertThat(lateOffsiteEventRepository.findByCaseId(caseRow.id())).hasSize(1);

        // 已结案件、原处罚决定与台账均不受影响
        assertThat(caseRepository.findById(caseRow.id()).status()).isEqualTo("CLOSED");
        assertThat(decisionRepository.findById(decision.decisionId()).status()).isEqualTo("ACTIVE");
        assertThat(decisionRepository.findByCaseId(caseRow.id())).hasSize(1);
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("自动生成的处罚决定沿用复检语义：推翻时同事务撤销与退费")
    void autoDecisionReviewOverturnRevokesAndRefundsAtomically() {
        OffsiteWeighResponse ingested = offsiteService.ingest(
                offsite("OS-OT", PLATE_4AXLE, WEIGH_TIME, "40300.00", IMAGE, DEVICE_VALID));
        assertThat(ingested.status()).isEqualTo("AUTO_DECIDED");
        CaseRow caseRow = caseRepository.findByBizKey(
                PLATE_4AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME);
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);

        ReviewResponse applied = enforcementService.applyReview(new ReviewApplyRequest(
                "R-OS-OT", PLATE_4AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME, "非现场取证有异议"));
        assertThat(applied.status()).isEqualTo("UNDER_REVIEW");

        ReviewResponse concluded = enforcementService.concludeReview(new ReviewConcludeRequest(
                "RC-OS-OT", PLATE_4AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME,
                true, new BigDecimal("30000.00")));
        assertThat(concluded.status()).isEqualTo("OVERTURNED");

        // 原自动决定已撤销，记分与罚款在同一事务内退还，并生成撤销文书
        DecisionRow revoked = decisionRepository.findById(ingested.decision().decisionId());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isZero();
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("0.00");
        assertThat(decisionRepository.findByCaseId(caseRow.id()))
                .extracting(DecisionRow::docType)
                .containsExactly("PENALTY", "REVOCATION");
    }

    @Test
    @DisplayName("证据齐全但不超限：自动生成 NO_ACTION 文书，不罚款不记分")
    void completeEvidenceNoOverloadGeneratesNoAction() {
        OffsiteWeighResponse response = offsiteService.ingest(
                offsite("OS-NOACT", PLATE_2AXLE, WEIGH_TIME, "18000.00", IMAGE, DEVICE_VALID));

        assertThat(response.status()).isEqualTo("AUTO_DECIDED");
        assertThat(response.decision()).isNotNull();
        assertThat(response.decision().docType()).isEqualTo("NO_ACTION");
        assertThat(response.decision().fineAmount()).isEqualByComparingTo("0.00");
        assertThat(response.decision().points()).isZero();
        CaseRow caseRow = caseRepository.findByBizKey(
                PLATE_2AXLE, OffsiteRules.OFFSITE_STATION, WEIGH_TIME);
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isZero();
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("0.00");
    }
}
