package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResponse;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import com.example.starter.cosmetic.web.dto.TestReportRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 备案主生命周期 H2 集成测试：提交/形式审查/公示第10日期满/生效、
 * 报告3年恰满、浓度恰等、实质变更重新审查、简易变更引用原编号、
 * 幂等重放与同键异参409、注销终态、历史链与审查明细复算。
 */
class FilingLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private FilingPort filingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    private SubmitFilingRequest ordinary(String requestId, String product) {
        return FilingFixtures.ordinarySubmit(requestId, product);
    }

    @Test
    void ordinarySubmissionPassesFormReviewAndEntersPublicity() {
        FilingResultResponse result = filingService.submit(ordinary("REQ-1", "P-ORD-1"));

        assertThat(result.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(result.filing().filingNo()).isEqualTo("SYN-F-000001");
        assertThat(result.filing().publicNoticeDateUtc()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(result.filing().publicExpiresAtUtc()).isEqualTo(EXPIRES_EXPECTED);
        assertThat(result.reviewDetails()).isNotEmpty();
        assertThat(filingPort.findActiveSlot("P-ORD-1")).isEmpty();
    }

    @Test
    void publicityExpiresExactlyAtDayTenMidnightUtc() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-2", "P-EXP-1"));
        String filingNo = submitted.filing().filingNo();

        // 期满前 1 纳秒不生效
        clock.setInstant(EXPIRES_EXPECTED.minusNanos(1));
        assertThat(filingService.expireDuePublicNotices(null)).isZero();
        assertThat(filingService.getFiling(filingNo).status()).isEqualTo(FilingStatus.PUBLIC.name());

        // 恰第 10 日 24:00（=D+10 00:00 UTC）期满自动生效
        clock.setInstant(EXPIRES_EXPECTED);
        int effected = filingService.expireDuePublicNotices(null);

        assertThat(effected).isEqualTo(1);
        FilingResponse effective = filingService.getFiling(filingNo);
        assertThat(effective.status()).isEqualTo(FilingStatus.EFFECTIVE.name());
        assertThat(effective.effectiveAtUtc()).isEqualTo(EXPIRES_EXPECTED);
        assertThat(filingPort.findActiveSlot("P-EXP-1")).isPresent();
        assertThat(filingPort.findActiveSlot("P-EXP-1").orElseThrow().filingNo()).isEqualTo(filingNo);
    }

    @Test
    void specialReportExactlyThreeYearsOldAndConcentrationAtLimitPasses() {
        SubmitFilingRequest request = FilingFixtures.sunscreenSubmit(
                "REQ-3", "P-SUN-1", LocalDate.of(2023, 6, 30), true);

        FilingResultResponse result = filingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(result.reviewDetails())
                .anyMatch(d -> d.ruleCode().equals("REPORT_VALIDITY") && d.passed())
                .anyMatch(d -> d.ruleCode().equals("RESTRICTED_CONCENTRATION") && d.passed());
    }

    @Test
    void specialReportOneDayExpiredRejected() {
        SubmitFilingRequest request = FilingFixtures.sunscreenSubmit(
                "REQ-4", "P-SUN-2", LocalDate.of(2023, 6, 29), true);

        FilingResultResponse result = filingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(result.filing().rejectReason()).contains("REPORT_VALIDITY");
        assertThat(filingPort.findActiveSlot("P-SUN-2")).isEmpty();
    }

    @Test
    void unsupportedClaimRejectedAtFormReview() {
        SubmitFilingRequest request = new SubmitFilingRequest("REQ-5", "P-BAD", "违规产品",
                FilingFixtures.ordinaryFormulation(), List.of("MEDICAL_CURE"), List.of(),
                null, null, null, null);

        FilingResultResponse rejected = filingService.submit(request);

        assertThat(rejected.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(rejected.filing().rejectReason()).contains("不支持的功效宣称");
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        FilingResultResponse first = filingService.submit(ordinary("REQ-IDEM-1", "P-IDEM-1"));
        FilingResultResponse replay = filingService.submit(ordinary("REQ-IDEM-1", "P-IDEM-1"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.filing().filingNo()).isEqualTo(first.filing().filingNo());
        assertThat(replay.atUtc()).isEqualTo(first.atUtc());
        assertThat(filingPort.findByProduct("P-IDEM-1")).hasSize(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturnsConflict() {
        filingService.submit(ordinary("REQ-IDEM-2", "P-IDEM-2"));

        SubmitFilingRequest changed = new SubmitFilingRequest("REQ-IDEM-2", "P-IDEM-2", "改名产品",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                null, null, null, null);

        assertThatThrownBy(() -> filingService.submit(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("409");
        assertThat(filingPort.findByProduct("P-IDEM-2")).hasSize(1);
    }

    @Test
    void simplePackagingChangeKeepsFilingNoAndSwitchesVersionAtomically() {
        // 先生效
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-SC-1", "P-SIMPLE-1"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        ChangeFilingRequest change = FilingFixtures.packagingChange("REQ-SC-2", "新包装文字：请置于阴凉处");
        FilingResultResponse result = filingService.change("P-SIMPLE-1", change);

        String filingNo = submitted.filing().filingNo();
        assertThat(result.filing().changeType()).isEqualTo("SIMPLE");
        assertThat(result.filing().status()).isEqualTo(FilingStatus.EFFECTIVE.name());
        assertThat(result.filing().filingNo()).isEqualTo(filingNo);
        assertThat(result.filing().versionNo()).isEqualTo(2);
        assertThat(result.filing().parentFilingNo()).isEqualTo(filingNo);
        assertThat(result.filing().packagingText()).contains("新包装文字");

        // 同事务：旧版本 SUPERSEDED，槽位指向新版本
        assertThat(filingPort.findVersion(filingNo, 1).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);
        assertThat(filingPort.findActiveSlot("P-SIMPLE-1").orElseThrow().versionNo()).isEqualTo(2);
    }

    @Test
    void substantiveChangeTriggersNewFilingNoReReviewAndAtomicSwitchOnExpiry() {
        // 原普通备案生效
        filingService.submit(ordinary("REQ-SUB-1", "P-SUBST-1"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 实质变更：新增防晒宣称（配方也变化），须重新审查并生成新备案编号
        List<FormulationItemRequest> newFormulation = FilingFixtures.sunscreenFormulation();
        List<TestReportRequest> newReports = List.of(
                FilingFixtures.sunscreenReport("R-SUB-1", LocalDate.of(2025, 1, 1), true));
        ChangeFilingRequest change = new ChangeFilingRequest("REQ-SUB-2", null,
                newFormulation, List.of("MOISTURIZING", "SUNSCREEN"), newReports, "包装不变",
                null, null, null, null, null);

        FilingResultResponse changed = filingService.change("P-SUBST-1", change);

        String oldNo = "SYN-F-000001";
        String newNo = "SYN-F-000002";
        assertThat(changed.filing().filingNo()).isEqualTo(newNo);
        assertThat(changed.filing().parentFilingNo()).isEqualTo(oldNo);
        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        // 新版本公示期间旧备案仍有效
        assertThat(filingPort.findVersion(oldNo, 1).orElseThrow().status())
                .isEqualTo(FilingStatus.EFFECTIVE);
        assertThat(filingPort.findActiveSlot("P-SUBST-1").orElseThrow().filingNo()).isEqualTo(oldNo);

        // 新版本公示期满：同事务新版本生效 + 旧版本 SUPERSEDED + 槽位切换
        Instant newExpires = changed.filing().publicExpiresAtUtc();
        clock.setInstant(newExpires);
        filingService.expireDuePublicNotices(null);

        assertThat(filingPort.findVersion(newNo, 2).orElseThrow().status())
                .isEqualTo(FilingStatus.EFFECTIVE);
        assertThat(filingPort.findVersion(oldNo, 1).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);
        var slot = filingPort.findActiveSlot("P-SUBST-1").orElseThrow();
        assertThat(slot.filingNo()).isEqualTo(newNo);
        assertThat(slot.versionNo()).isEqualTo(2);
    }

    @Test
    void substantiveChangeFailingReviewDoesNotDisturbActiveFiling() {
        filingService.submit(ordinary("REQ-SUBF-1", "P-SUBSTF-1"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 新宣称防晒但提供超期报告 -> 新版本形式驳回，旧备案保持有效
        ChangeFilingRequest change = new ChangeFilingRequest("REQ-SUBF-2", null,
                FilingFixtures.sunscreenFormulation(), List.of("SUNSCREEN"),
                List.of(FilingFixtures.sunscreenReport("R-SUBF", LocalDate.of(2020, 1, 1), true)),
                null, null, null, null, null, null);

        FilingResultResponse result = filingService.change("P-SUBSTF-1", change);
        assertThat(result.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(result.filing().filingNo()).isEqualTo("SYN-F-000002");
        assertThat(filingPort.findVersion("SYN-F-000001", 1).orElseThrow().status())
                .isEqualTo(FilingStatus.EFFECTIVE);
        assertThat(filingPort.findActiveSlot("P-SUBSTF-1").orElseThrow().filingNo())
                .isEqualTo("SYN-F-000001");
    }

    @Test
    void withdrawalIsTerminalAndRepeatsIdempotentlyAndBlocksNewClaims() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-W-1", "P-WD-1"));
        String filingNo = submitted.filing().filingNo();
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        FilingResultResponse withdrawn = filingService.withdraw(filingNo, "REQ-W-2", "企业主动注销");
        assertThat(withdrawn.filing().status()).isEqualTo(FilingStatus.WITHDRAWN.name());
        assertThat(filingPort.findActiveSlot("P-WD-1")).isEmpty();

        // 重复注销幂等成功
        FilingResultResponse again = filingService.withdraw(filingNo, "REQ-W-2", "企业主动注销");
        assertThat(again.idempotentReplay()).isTrue();
        assertThat(again.filing().status()).isEqualTo(FilingStatus.WITHDRAWN.name());

        // 历史备案可查
        assertThat(filingService.getFiling(filingNo).status()).isEqualTo(FilingStatus.WITHDRAWN.name());

        // 注销后新宣称审查一律拒绝
        FilingResultResponse newClaim = filingService.submit(ordinary("REQ-W-3", "P-WD-1"));
        assertThat(newClaim.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(newClaim.filing().rejectReason()).contains("注销后新宣称审查一律拒绝");
    }

    @Test
    void objectionUpheldLeadsToTerminalRejection() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-O-1", "P-OBJ-1"));
        String filingNo = submitted.filing().filingNo();

        FilingResultResponse objection = filingService.acceptObjection(
                filingNo, Instant.parse("2026-07-05T10:00:00Z"), "消费者对功效提出异议");
        assertThat(objection.filing().status()).isEqualTo(FilingStatus.OBJECTION_REVIEW.name());

        FilingResultResponse resolved = filingService.resolveObjection(filingNo, true, "异议成立，宣称依据不足");
        assertThat(resolved.filing().status()).isEqualTo(FilingStatus.REJECTED_AFTER_OBJECTION.name());
        assertThat(filingPort.findActiveSlot("P-OBJ-1")).isEmpty();

        // 已终态，期满扫描不得使其生效
        clock.setInstant(EXPIRES_EXPECTED);
        assertThat(filingService.expireDuePublicNotices(null)).isZero();
        assertThat(filingService.getFiling(filingNo).status())
                .isEqualTo(FilingStatus.REJECTED_AFTER_OBJECTION.name());
    }

    @Test
    void objectionNotUpheldReopensPublicityAndStillExpiresOnOriginalSchedule() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-O-2", "P-OBJ-2"));
        String filingNo = submitted.filing().filingNo();

        filingService.acceptObjection(filingNo, Instant.parse("2026-07-03T10:00:00Z"), "异议待核");
        FilingResultResponse resolved = filingService.resolveObjection(filingNo, false, "异议不成立");
        assertThat(resolved.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());

        clock.setInstant(EXPIRES_EXPECTED);
        assertThat(filingService.expireDuePublicNotices(null)).isEqualTo(1);
        assertThat(filingService.getFiling(filingNo).status()).isEqualTo(FilingStatus.EFFECTIVE.name());
    }

    @Test
    void lateObjectionAfterExpiryIsRejected() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-O-3", "P-OBJ-3"));
        String filingNo = submitted.filing().filingNo();
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        assertThatThrownBy(() -> filingService.acceptObjection(
                filingNo, EXPIRES_EXPECTED.plusSeconds(1), "迟到异议"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void productHistoryAndReviewDetailsAreRecoverable() {
        FilingResultResponse submitted = filingService.submit(ordinary("REQ-H-1", "P-HIST-1"));
        String filingNo = submitted.filing().filingNo();

        List<FilingResponse> history = filingService.getProductHistory("P-HIST-1");
        assertThat(history).hasSize(1);
        assertThat(filingService.getReviews(filingNo, 1))
                .extracting("ruleCode")
                .contains("DOC_COMPLETE", "CLAIM_CATALOG", "REPORT_VALIDITY", "RESTRICTED_CONCENTRATION");
    }

    @Test
    void secondSubmissionWhileInPublicityRejected() {
        filingService.submit(ordinary("REQ-DUP-1", "P-DUP-1"));

        FilingResultResponse second = filingService.submit(ordinary("REQ-DUP-2", "P-DUP-1"));
        assertThat(second.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(second.filing().rejectReason()).contains("PRODUCT_ALREADY_IN_FLIGHT");
    }

    @Test
    void changeWithoutAnyActualDifferenceRejected() {
        filingService.submit(ordinary("REQ-NC-1", "P-NC-1"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        ChangeFilingRequest noop = new ChangeFilingRequest("REQ-NC-2", null, null, null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> filingService.change("P-NC-1", noop))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("NO_CHANGE"));
    }
}
