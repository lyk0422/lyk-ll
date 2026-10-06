package com.example.starter.weight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.CloseCaseRequest;
import com.example.starter.weight.api.dto.OffsiteWeighRequest;
import com.example.starter.weight.api.dto.OffsiteWeighResponse;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewConcludeRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.LedgerRepository;
import com.example.starter.weight.repo.OffsiteLateEventRepository;
import com.example.starter.weight.repo.OffsiteWeighRecordRepository;
import com.example.starter.weight.repo.WeighRecordRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.service.EnforcementService;
import com.example.starter.weight.service.OffsiteEnforcementService;

/**
 * 非现场执法接入集成测试（真实 H2）：证据链三项校验、校准有效期、窗口去重只保留首次、
 * 幂等重放与异参 409、自动生成处罚沿用既有处罚/复检语义、结案后迟到只记事件。
 */
class OffsiteEnforcementIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE_4AXLE = "沪A10001";   // AXLE_4，限值 31000kg
    private static final String LANE = "ST001-L1";
    private static final String DEVICE = "DEV-ST001-L1";     // 校准有效期 2026-01-01 ~ 2026-12-31
    private static final String DEVICE_EXPIRED = "DEV-ST002-L1"; // 校准有效期止 2026-06-30
    private static final String LANE_EXPIRED = "ST002-L1";
    private static final String STATION = "ST001";
    private static final String IMAGE = "IMG-20260901-0001";

    @Autowired
    private OffsiteEnforcementService offsiteService;
    @Autowired
    private EnforcementService enforcementService;
    @Autowired
    private OffsiteWeighRecordRepository offsiteRepository;
    @Autowired
    private OffsiteLateEventRepository offsiteLateEventRepository;
    @Autowired
    private WeighRecordRepository weighRecordRepository;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private DecisionRepository decisionRepository;
    @Autowired
    private LedgerRepository ledgerRepository;

    private OffsiteWeighRequest offsite(String requestId, String plate, String lane, String device,
                                        Instant time, String totalKg, String imageRef) {
        return new OffsiteWeighRequest(requestId, plate, lane, device, time,
                new BigDecimal(totalKg), imageRef);
    }

    @Test
    @DisplayName("证据链齐全：自动生成处罚决定，沿用既有处罚语义并登记台账")
    void completeEvidenceAutoGeneratesDecision() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-OK-1", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));

        assertThat(response.evidenceStatus()).isEqualTo("COMPLETE");
        assertThat(response.missingItems()).isEmpty();
        assertThat(response.overloadRate()).isEqualByComparingTo("0.30");
        assertThat(response.tier()).isEqualTo("FINE_POINTS");
        assertThat(response.caseNo()).isNotNull();
        assertThat(response.decisionDocNo()).isNotNull();
        assertThat(response.lateEvent()).isFalse();
        assertThat(response.deduped()).isFalse();

        // 自动生成的处罚决定沿用既有处罚语义：PENALTY、罚款 2000、记 3 分
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        assertThat(caseRow).isNotNull();
        var docs = decisionRepository.findByCaseId(caseRow.id());
        assertThat(docs).hasSize(1);
        DecisionRow doc = docs.get(0);
        assertThat(doc.docType()).isEqualTo("PENALTY");
        assertThat(doc.fineAmount()).isEqualByComparingTo("2000.00");
        assertThat(doc.points()).isEqualTo(3);
        assertThat(doc.status()).isEqualTo("ACTIVE");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");

        // 非现场数据同步落入过磅记录，作为判定依据
        var authoritative = weighRecordRepository.findAuthoritative(PLATE_4AXLE, STATION, WEIGH_TIME);
        assertThat(authoritative).isNotNull();
        assertThat(authoritative.mode()).isEqualTo("DYNAMIC");
    }

    @Test
    @DisplayName("证据缺项：缺合成影像标识不得自动生成处罚决定，转人工待补")
    void missingImageRefGoesManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-NOIMG", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", null));

        assertThat(response.evidenceStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(response.missingItems()).containsExactly("IMAGE_REF");
        assertThat(response.caseNo()).isNull();
        assertThat(response.decisionDocNo()).isNull();

        // 不产生案件、过磅记录与文书
        assertThat(caseRepository.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isZero();
    }

    @Test
    @DisplayName("证据缺项：设备状态记录缺失（未知设备）转人工待补")
    void missingDeviceStatusGoesManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-NODEV", PLATE_4AXLE, "ST009-L9", "DEV-UNKNOWN", WEIGH_TIME, "40300.00", IMAGE));

        assertThat(response.evidenceStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(response.missingItems()).containsExactly("DEVICE_STATUS");
        assertThat(caseRepository.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isZero();
    }

    @Test
    @DisplayName("校准有效期外：数据不得进入证据链，转人工待补")
    void expiredCalibrationGoesManualPending() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-EXPCAL", PLATE_4AXLE, LANE_EXPIRED, DEVICE_EXPIRED, WEIGH_TIME, "40300.00", IMAGE));

        assertThat(response.evidenceStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(response.missingItems()).containsExactly("CALIBRATION_EXPIRED");
        assertThat(response.caseNo()).isNull();
        assertThat(caseRepository.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isZero();
    }

    @Test
    @DisplayName("窗口去重：与现场记录按车牌与时刻窗口去重，窗口内只保留首次")
    void duplicateOfOnsiteWithinWindowIsDeduped() {
        // 现场动态过磅在先（T）
        enforcementService.submitWeigh(new WeighRequest("W-ONSITE", PLATE_4AXLE, STATION,
                WEIGH_TIME, WeighMode.DYNAMIC, new BigDecimal("40300.00")));

        // 非现场同车牌 T+2 分钟（窗口 ±5 分钟内）：去重，只保留首次
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-DUP-ONSITE", PLATE_4AXLE, LANE, DEVICE,
                WEIGH_TIME.plus(2, ChronoUnit.MINUTES), "40300.00", IMAGE));

        assertThat(response.evidenceStatus()).isEqualTo("DUPLICATE");
        assertThat(response.deduped()).isTrue();
        assertThat(response.caseNo()).isNull();
        assertThat(response.decisionDocNo()).isNull();

        // 只保留首次：无新案件、无新过磅记录、无文书
        assertThat(caseRepository.findAll()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isZero();
    }

    @Test
    @DisplayName("窗口去重：非现场重复接入只保留首次，不重复立案与生成文书")
    void duplicateOffsiteWithinWindowIsDeduped() {
        OffsiteWeighResponse first = offsiteService.ingest(offsite(
                "OS-FIRST", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        assertThat(first.evidenceStatus()).isEqualTo("COMPLETE");

        // 同车牌 T+3 分钟再次接入（窗口内）：去重
        OffsiteWeighResponse second = offsiteService.ingest(offsite(
                "OS-SECOND", PLATE_4AXLE, LANE, DEVICE,
                WEIGH_TIME.plus(3, ChronoUnit.MINUTES), "40300.00", IMAGE));
        assertThat(second.evidenceStatus()).isEqualTo("DUPLICATE");
        assertThat(second.deduped()).isTrue();

        // 窗口外（T+6 分钟）为新的称重事件：正常立案
        OffsiteWeighResponse third = offsiteService.ingest(offsite(
                "OS-THIRD", PLATE_4AXLE, LANE, DEVICE,
                WEIGH_TIME.plus(6, ChronoUnit.MINUTES), "40300.00", IMAGE));
        assertThat(third.evidenceStatus()).isEqualTo("COMPLETE");
        assertThat(third.caseNo()).isNotEqualTo(first.caseNo());

        // 首次与窗口外各一案一文；窗口内重复不产生新文书
        assertThat(caseRepository.findAll()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM offsite_weigh_record", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("幂等：同键同参重放首次结果；同键异参 409 且不产生新文书")
    void idempotentReplayAndConflict() {
        OffsiteWeighResponse first = offsiteService.ingest(offsite(
                "OS-IDEM", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        OffsiteWeighResponse replay = offsiteService.ingest(offsite(
                "OS-IDEM", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.recordId()).isEqualTo(first.recordId());
        assertThat(replay.decisionDocNo()).isEqualTo(first.decisionDocNo());

        // 同键异参：409，且不产生新记录与新文书
        assertThatThrownBy(() -> offsiteService.ingest(offsite(
                "OS-IDEM", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "50000.00", IMAGE)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.status()).isEqualTo(409);
                    assertThat(ae.code()).isEqualTo("IDEMPOTENT_PARAM_CONFLICT");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM offsite_weigh_record", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("结案后迟到：非现场数据只记迟到事件，不改已结案件")
    void lateOffsiteAfterCloseOnlyRecordsEvent() {
        // 完整非现场流程：立案并自动生成处罚决定
        OffsiteWeighResponse first = offsiteService.ingest(offsite(
                "OS-LATE-1", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        assertThat(first.evidenceStatus()).isEqualTo("COMPLETE");
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);

        enforcementService.closeCase(new CloseCaseRequest("C-LATE", PLATE_4AXLE, STATION, WEIGH_TIME));

        // 结案后同事件非现场数据迟到：只记迟到事件
        OffsiteWeighResponse late = offsiteService.ingest(offsite(
                "OS-LATE-2", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        assertThat(late.evidenceStatus()).isEqualTo("LATE");
        assertThat(late.lateEvent()).isTrue();
        assertThat(late.caseNo()).isEqualTo(first.caseNo());
        assertThat(late.decisionDocNo()).isNull();
        assertThat(offsiteLateEventRepository.findByCaseId(caseRow.id())).hasSize(1);

        // 已结案件、处罚决定与台账均不受影响
        assertThat(caseRepository.findById(caseRow.id()).status()).isEqualTo("CLOSED");
        var docs = decisionRepository.findByCaseId(caseRow.id());
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).status()).isEqualTo("ACTIVE");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("自动处罚的复检推翻：撤销与退费在同一事务内完成，文书链完整")
    void autoDecisionOverturnRevokesAtomically() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-OT", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "40300.00", IMAGE));
        assertThat(response.evidenceStatus()).isEqualTo("COMPLETE");
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        DecisionRow autoDoc = decisionRepository.findByCaseId(caseRow.id()).get(0);
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);

        // 沿用既有复检语义：送达后 7 日内申请复检
        mutableClock.setInstant(autoDoc.servedAt().plus(2, ChronoUnit.DAYS));
        ReviewResponse review = enforcementService.applyReview(new ReviewApplyRequest(
                "R-OS-OT", PLATE_4AXLE, STATION, WEIGH_TIME, "非现场证据有异议"));
        assertThat(review.status()).isEqualTo("UNDER_REVIEW");

        ReviewResponse concluded = enforcementService.concludeReview(new ReviewConcludeRequest(
                "RC-OS-OT", PLATE_4AXLE, STATION, WEIGH_TIME, true, new BigDecimal("30000.00")));
        assertThat(concluded.status()).isEqualTo("OVERTURNED");

        // 同一事务：原决定撤销、记分罚款退还、撤销文书生成
        assertThat(decisionRepository.findById(autoDoc.id()).status()).isEqualTo("REVOKED");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isZero();
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("0.00");
        var docs = decisionRepository.findByCaseId(caseRow.id());
        assertThat(docs).extracting(DecisionRow::docType).containsExactly("PENALTY", "REVOCATION");
    }

    @Test
    @DisplayName("不超限的非现场数据：证据齐全时自动生成 NO_ACTION 文书")
    void noOverloadGeneratesNoAction() {
        OffsiteWeighResponse response = offsiteService.ingest(offsite(
                "OS-NOACT", PLATE_4AXLE, LANE, DEVICE, WEIGH_TIME, "31000.00", IMAGE));

        assertThat(response.evidenceStatus()).isEqualTo("COMPLETE");
        assertThat(response.tier()).isEqualTo("NO_OVERLOAD");
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        var docs = decisionRepository.findByCaseId(caseRow.id());
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).docType()).isEqualTo("NO_ACTION");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isZero();
    }
}
