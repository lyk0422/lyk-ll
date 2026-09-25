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
import com.example.starter.weight.api.dto.CaseResponse;
import com.example.starter.weight.api.dto.CloseCaseRequest;
import com.example.starter.weight.api.dto.DecisionRequest;
import com.example.starter.weight.api.dto.DecisionResponse;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewConcludeRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.api.dto.WeighResponse;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.LateWeighEventRepository;
import com.example.starter.weight.repo.LedgerRepository;
import com.example.starter.weight.repo.ReviewRepository;
import com.example.starter.weight.repo.WeighRecordRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.service.EnforcementService;

/**
 * 治超执法主流程集成测试（真实 H2）：超限率恰 0、恰 30% 分档、去重口径、
 * 静态复检优先、同键重放、复检第 7 日边界、撤销原子性、结案终态。
 */
class EnforcementFlowIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE_4AXLE = "沪A10001";   // AXLE_4，限值 31000kg
    private static final String PLATE_2AXLE = "苏C30003";   // AXLE_2，限值 18000kg
    private static final String STATION = "ST001";

    @Autowired
    private EnforcementService service;
    @Autowired
    private WeighRecordRepository weighRecordRepository;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private DecisionRepository decisionRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LateWeighEventRepository lateWeighEventRepository;

    private WeighRequest weigh(String requestId, String plate, String station, Instant time,
                               WeighMode mode, String totalKg) {
        return new WeighRequest(requestId, plate, station, time, mode, new BigDecimal(totalKg));
    }

    private DecisionRequest decision(String requestId, String plate, String station, Instant time) {
        return new DecisionRequest(requestId, plate, station, time);
    }

    @Test
    @DisplayName("超限率恰为 0：判不超限，生成 NO_ACTION 文书")
    void exactlyZeroRateIsNotOverload() {
        WeighResponse weigh = service.submitWeigh(
                weigh("W-ZERO", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "31000.00"));
        assertThat(weigh.overloadRate()).isEqualByComparingTo("0.00");
        assertThat(weigh.tier()).isEqualTo("NO_OVERLOAD");

        DecisionResponse decision = service.issueDecision(
                decision("D-ZERO", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(decision.docType()).isEqualTo("NO_ACTION");
        assertThat(decision.fineAmount()).isEqualByComparingTo("0.00");
        assertThat(decision.points()).isZero();
    }

    @Test
    @DisplayName("超限率恰为 30%：按罚款记分档处理")
    void exactlyThirtyPercentIsFineTier() {
        WeighResponse weigh = service.submitWeigh(
                weigh("W-30", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        assertThat(weigh.overloadRate()).isEqualByComparingTo("0.30");
        assertThat(weigh.tier()).isEqualTo("FINE_POINTS");

        DecisionResponse decision = service.issueDecision(
                decision("D-30", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(decision.docType()).isEqualTo("PENALTY");
        assertThat(decision.fineAmount()).isEqualByComparingTo("2000.00");
        assertThat(decision.points()).isEqualTo(3);
    }

    @Test
    @DisplayName("超限率不足 30%：责令卸载放行，不罚款不记分")
    void belowThirtyPercentIsUnloadRelease() {
        WeighResponse weigh = service.submitWeigh(
                weigh("W-29", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "39990.00"));
        assertThat(weigh.overloadRate()).isEqualByComparingTo("0.29");
        assertThat(weigh.tier()).isEqualTo("UNLOAD_RELEASE");

        DecisionResponse decision = service.issueDecision(
                decision("D-29", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(decision.docType()).isEqualTo("UNLOAD_RELEASE");
        assertThat(decision.fineAmount()).isEqualByComparingTo("0.00");
        assertThat(decision.points()).isZero();
    }

    @Test
    @DisplayName("去重口径：同车牌+检测站+过磅时刻+方式的重复上报不新建记录")
    void duplicateWeighByBizKeyIsDeduped() {
        WeighResponse first = service.submitWeigh(
                weigh("W-DUP-1", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        WeighResponse second = service.submitWeigh(
                weigh("W-DUP-2", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        assertThat(second.deduped()).isTrue();
        assertThat(second.recordId()).isEqualTo(first.recordId());
        assertThat(weighRecordRepository.findAllByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME))
                .hasSize(1);
    }

    @Test
    @DisplayName("静态复检优先：静态取代动态为判定依据，动态保留可查")
    void staticRecheckOverridesDynamic() {
        WeighResponse dynamic = service.submitWeigh(
                weigh("W-DYN", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        assertThat(dynamic.tier()).isEqualTo("FINE_POINTS");

        WeighResponse statik = service.submitWeigh(
                weigh("W-STA", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.STATIC, "31000.00"));
        assertThat(statik.role()).isEqualTo("AUTHORITATIVE");
        assertThat(statik.tier()).isEqualTo("NO_OVERLOAD");

        var all = weighRecordRepository.findAllByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        assertThat(all).hasSize(2);
        var dynamicRow = all.stream().filter(r -> r.mode().equals("DYNAMIC")).findFirst().orElseThrow();
        assertThat(dynamicRow.role()).isEqualTo("SUPERSEDED");

        // 判定以静态复检为准：超限率 0，生成不超限文书
        DecisionResponse decision = service.issueDecision(
                decision("D-STA", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(decision.docType()).isEqualTo("NO_ACTION");
        assertThat(decision.overloadRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("后到动态记录降级保留：静态已在案时动态不改判")
    void lateDynamicIsSupersededWhenStaticExists() {
        service.submitWeigh(weigh("W-STA-FIRST", PLATE_4AXLE, STATION, WEIGH_TIME,
                WeighMode.STATIC, "31000.00"));
        WeighResponse dynamic = service.submitWeigh(
                weigh("W-DYN-LATE", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        assertThat(dynamic.role()).isEqualTo("SUPERSEDED");
        assertThat(weighRecordRepository.findAuthoritative(PLATE_4AXLE, STATION, WEIGH_TIME).mode())
                .isEqualTo("STATIC");
    }

    @Test
    @DisplayName("同键同参重放返回首次结果；同键异参返回 409 且不产生新记录")
    void idempotentReplayAndConflict() {
        WeighResponse first = service.submitWeigh(
                weigh("W-IDEM", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        WeighResponse replay = service.submitWeigh(
                weigh("W-IDEM", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.recordId()).isEqualTo(first.recordId());

        assertThatThrownBy(() -> service.submitWeigh(
                weigh("W-IDEM", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "50000.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.status()).isEqualTo(409);
                    assertThat(ae.code()).isEqualTo("IDEMPOTENT_PARAM_CONFLICT");
                });
        assertThat(weighRecordRepository.findAllByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME))
                .hasSize(1);

        // 处罚决定同键异参同样 409 且不产生新文书
        service.issueDecision(decision("D-IDEM", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThatThrownBy(() -> service.issueDecision(
                decision("D-IDEM", PLATE_2AXLE, STATION, WEIGH_TIME)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));
        assertThat(decisionRepository.findByCaseId(
                caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME).id())).hasSize(1);
    }

    @Test
    @DisplayName("复检期限：恰第 7 日（UTC 日界）受理，第 8 日拒绝")
    void reviewWindowSeventhUtcDayAcceptedEighthRejected() {
        service.submitWeigh(weigh("W-R7", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        DecisionResponse decision = service.issueDecision(
                decision("D-R7", PLATE_4AXLE, STATION, WEIGH_TIME));
        Instant servedDayStart = decision.servedAt().truncatedTo(ChronoUnit.DAYS);

        // 恰第 7 日 23:59:59 UTC：受理
        mutableClock.setInstant(servedDayStart.plus(7, ChronoUnit.DAYS).plusSeconds(86399));
        ReviewResponse review = service.applyReview(new ReviewApplyRequest(
                "R-7D", PLATE_4AXLE, STATION, WEIGH_TIME, "对称重结果有异议"));
        assertThat(review.status()).isEqualTo("UNDER_REVIEW");

        // 第 8 日 00:00:00 UTC：逾期拒绝（以该案件自身送达日为基准）
        service.submitWeigh(weigh("W-R8", PLATE_2AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "23400.00"));
        DecisionResponse decision8 = service.issueDecision(
                decision("D-R8", PLATE_2AXLE, STATION, WEIGH_TIME));
        Instant servedDayStart8 = decision8.servedAt().truncatedTo(ChronoUnit.DAYS);
        mutableClock.setInstant(servedDayStart8.plus(8, ChronoUnit.DAYS));
        assertThatThrownBy(() -> service.applyReview(new ReviewApplyRequest(
                "R-8D", PLATE_2AXLE, STATION, WEIGH_TIME, "逾期申请")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.status()).isEqualTo(422);
                    assertThat(ae.code()).isEqualTo("REVIEW_WINDOW_EXPIRED");
                });
    }

    @Test
    @DisplayName("复检推翻：同一事务撤销原决定、退还记分罚款并生成撤销文书")
    void overturnRevokesAtomically() {
        service.submitWeigh(weigh("W-OT", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        DecisionResponse decision = service.issueDecision(
                decision("D-OT", PLATE_4AXLE, STATION, WEIGH_TIME));
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");

        mutableClock.setInstant(decision.servedAt().plus(2, ChronoUnit.DAYS));
        service.applyReview(new ReviewApplyRequest("R-OT", PLATE_4AXLE, STATION, WEIGH_TIME, "复磅不超限"));

        ReviewResponse concluded = service.concludeReview(new ReviewConcludeRequest(
                "RC-OT", PLATE_4AXLE, STATION, WEIGH_TIME, true, new BigDecimal("30000.00")));
        assertThat(concluded.status()).isEqualTo("OVERTURNED");

        // 原决定已撤销
        DecisionRow revoked = decisionRepository.findByIdForUpdate(decision.decisionId());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isNotNull();
        // 记分与罚款净额为 0（已退还）
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isZero();
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("0.00");
        // 撤销文书已生成，文书链完整可复算
        var docs = decisionRepository.findByCaseId(caseRow.id());
        assertThat(docs).extracting(DecisionRow::docType).containsExactly("PENALTY", "REVOCATION");
        var revocation = docs.get(1);
        assertThat(revocation.totalKg()).isEqualByComparingTo("30000.00");
        assertThat(revocation.overloadRate()).isEqualByComparingTo("-0.03");
    }

    @Test
    @DisplayName("撤销原子性：撤销文书生成失败时整体回滚，不得罚款已退而原决定仍生效")
    void overturnFailureRollsBackEverything() {
        service.submitWeigh(weigh("W-RB", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        DecisionResponse decision = service.issueDecision(
                decision("D-RB", PLATE_4AXLE, STATION, WEIGH_TIME));
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        service.applyReview(new ReviewApplyRequest("R-RB", PLATE_4AXLE, STATION, WEIGH_TIME, "复磅不超限"));

        // 预置同幂等键文书，使撤销文书插入触发唯一约束冲突，模拟撤销链中途失败
        DecisionRow blocker = new DecisionRow(
                null, "REV-RC-RB", "BLOCK-FP", "WS-BLOCK", caseRow.id(), "REVOCATION", "FINE_POINTS",
                new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO,
                BigDecimal.ZERO, 0, "ACTIVE", decision.servedAt(), null, decision.servedAt());
        decisionRepository.insert(blocker);

        assertThatThrownBy(() -> service.concludeReview(new ReviewConcludeRequest(
                "RC-RB", PLATE_4AXLE, STATION, WEIGH_TIME, true, new BigDecimal("30000.00"))))
                .isInstanceOf(Exception.class);

        // 整体回滚：原决定仍生效、记分罚款未退还、复检仍待结论
        assertThat(decisionRepository.findByIdForUpdate(decision.decisionId()).status())
                .isEqualTo("ACTIVE");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        assertThat(ledgerRepository.sumFineByCase(caseRow.id())).isEqualByComparingTo("2000.00");
        assertThat(reviewRepository.findByCaseId(caseRow.id()).status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    @DisplayName("复检维持：原处罚继续生效，台账不变")
    void upholdKeepsPenaltyActive() {
        service.submitWeigh(weigh("W-UP", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        DecisionResponse decision = service.issueDecision(
                decision("D-UP", PLATE_4AXLE, STATION, WEIGH_TIME));
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);
        service.applyReview(new ReviewApplyRequest("R-UP", PLATE_4AXLE, STATION, WEIGH_TIME, "申请复检"));

        ReviewResponse concluded = service.concludeReview(new ReviewConcludeRequest(
                "RC-UP", PLATE_4AXLE, STATION, WEIGH_TIME, false, null));
        assertThat(concluded.status()).isEqualTo("UPHELD");
        assertThat(decisionRepository.findByIdForUpdate(decision.decisionId()).status())
                .isEqualTo("ACTIVE");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
    }

    @Test
    @DisplayName("结案终态：重复结案幂等成功；结案后迟到过磅只记迟到事件，不改判")
    void closedCaseIsTerminalAndLateWeighOnlyEvidence() {
        service.submitWeigh(weigh("W-CL", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        DecisionResponse decision = service.issueDecision(
                decision("D-CL", PLATE_4AXLE, STATION, WEIGH_TIME));
        CaseRow caseRow = caseRepository.findByBizKey(PLATE_4AXLE, STATION, WEIGH_TIME);

        CaseResponse closed = service.closeCase(
                new CloseCaseRequest("C-CL", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.replayed()).isFalse();

        // 重复结案：幂等成功，不产生新文书
        CaseResponse again = service.closeCase(
                new CloseCaseRequest("C-CL", PLATE_4AXLE, STATION, WEIGH_TIME));
        assertThat(again.status()).isEqualTo("CLOSED");
        assertThat(again.replayed()).isTrue();
        assertThat(again.decisions()).hasSize(1);

        // 结案后迟到过磅（含静态复检）：只记迟到事件入证据备查，不改判
        WeighResponse late = service.submitWeigh(
                weigh("W-LATE", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.STATIC, "30000.00"));
        assertThat(late.lateEvent()).isTrue();
        assertThat(late.role()).isEqualTo("LATE_EVIDENCE");
        assertThat(lateWeighEventRepository.findByCaseId(caseRow.id())).hasSize(1);

        // 已结案件与处罚决定不受影响
        assertThat(caseRepository.findById(caseRow.id()).status()).isEqualTo("CLOSED");
        assertThat(decisionRepository.findByIdForUpdate(decision.decisionId()).status())
                .isEqualTo("ACTIVE");
        assertThat(ledgerRepository.sumPointsByCase(caseRow.id())).isEqualTo(3);
        // 结案后不能再生成新文书
        assertThatThrownBy(() -> service.issueDecision(
                decision("D-AFTER-CLOSE", PLATE_4AXLE, STATION, WEIGH_TIME)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("CASE_CLOSED"));
    }

    @Test
    @DisplayName("复检申请幂等：同键重放返回首次受理结果")
    void reviewApplyIdempotentReplay() {
        service.submitWeigh(weigh("W-RA", PLATE_4AXLE, STATION, WEIGH_TIME, WeighMode.DYNAMIC, "40300.00"));
        service.issueDecision(decision("D-RA", PLATE_4AXLE, STATION, WEIGH_TIME));

        ReviewResponse first = service.applyReview(
                new ReviewApplyRequest("R-RA", PLATE_4AXLE, STATION, WEIGH_TIME, "申请复检"));
        ReviewResponse replay = service.applyReview(
                new ReviewApplyRequest("R-RA", PLATE_4AXLE, STATION, WEIGH_TIME, "申请复检"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reviewId()).isEqualTo(first.reviewId());
    }
}
