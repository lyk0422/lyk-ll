package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionStatus;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildChangeRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildLabelRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitRequest;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.ReportPort;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.service.NotFoundException;
import com.example.starter.cosmetic.testsupport.ChildFilingFixtures;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 儿童分龄备案主流程 H2 集成测试：分档资料审查（缺项一次列全、零生效）、
 * 受理冻结、补正生成新修订、公示期满生效、实质/简易变更门禁、
 * expectedVersion 乐观锁、幂等重放与同键异参 409、失败不占键。
 */
class ChildFilingLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildFilingService childFilingService;

    @Autowired
    private ChildFilingPort childFilingPort;

    @Autowired
    private ReportPort adultReportPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    // ============================ 提交与分龄审查 ============================

    @Test
    void lowBandSubmissionAcceptedFrozenAndEntersPublicity() {
        ChildFilingResultResponse result =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-1", "PC-LOW-1"));

        assertThat(result.filing().filingNo()).isEqualTo("SYN-C-000001");
        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.IN_PUBLICITY.name());
        assertThat(result.filing().versionNo()).isEqualTo(1);
        assertThat(result.filing().currentRevisionNo()).isEqualTo(1);
        var revision = result.filing().currentRevision();
        assertThat(revision.status()).isEqualTo(ChildRevisionStatus.PUBLIC.name());
        // 受理冻结：分龄规则版本、标识文本摘要、报告清单
        assertThat(revision.ruleVersion()).isEqualTo("CHILD-AGE-2026A");
        assertThat(revision.labelDigest()).isNotBlank();
        assertThat(revision.safetyReports()).hasSize(1);
        assertThat(revision.labels()).hasSize(1);
        assertThat(revision.acceptedAtUtc()).isEqualTo(INITIAL_NOW);
        assertThat(revision.publicExpiresAtUtc()).isEqualTo(EXPIRES_EXPECTED);
        assertThat(result.evidence()).isNotEmpty();
    }

    @Test
    void crossBandSubmissionListsAllMissingItemsAndHasZeroEffect() {
        // 跨档 0~144 月，但只提供低龄档报告与标识：两档缺项须一次列全
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-2", "PC-CROSS-MISS",
                "合成儿童乳", 0, 144, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-2")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        String reason = result.filing().currentRevision().rejectReason();
        assertThat(reason).contains("BAND_37_144").contains("缺少独立儿童安全报告").contains("缺少适用年龄标识");
        assertThat(result.evidence())
                .anyMatch(d -> d.ruleCode().equals("CHILD_SAFETY_REPORT_PRESENT") && !d.passed())
                .anyMatch(d -> d.ruleCode().equals("CHILD_LABEL_PRESENT") && !d.passed());
        // 零生效：无生效修订，备案不进入公示
        assertThat(childFilingPort.findEffectiveRevision("SYN-C-000001")).isEmpty();
        assertThat(result.filing().currentRevision().status())
                .isEqualTo(ChildRevisionStatus.REJECTED.name());
    }

    @Test
    void adultEfficacyClaimRejectedInChildChannel() {
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-3", "PC-ADULT-CLAIM",
                "合成乳", 0, 36, List.of("MOISTURIZING"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-3")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        assertThat(result.filing().currentRevision().rejectReason())
                .contains("CHILD_CLAIM_CATALOG").contains("MOISTURIZING");
    }

    @Test
    void unsupportedChildEfficacyRejected() {
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-4", "PC-UNSUPP",
                "合成乳", 0, 36, List.of("CH_WHITENING"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-4")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        assertThat(result.filing().currentRevision().rejectReason()).contains("不支持");
    }

    @Test
    void adultEfficacyReportCannotSubstituteChildSafetyReport() {
        // 成人功效评价报告已登记，但不能替代儿童安全报告
        adultReportPort.upsert(new TestReportRecord("AR-1", "SUNSCREEN",
                LocalDate.of(2026, 1, 1), true, "成人防晒功效评价报告"));
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-5", "PC-ADULT-REPORT",
                "合成乳", 0, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(), List.of(ChildFilingFixtures.lowBandLabel()), null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        assertThat(result.filing().currentRevision().rejectReason())
                .contains("CHILD_SAFETY_REPORT_PRESENT").contains("成人功效报告不能替代");
    }

    @Test
    void safetyReportMustExplicitlyCoverAppliedAgeRange() {
        // 申请 0~36 月，报告仅覆盖 0~12 月：未明确覆盖申请年龄范围
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-6", "PC-COVER",
                "合成乳", 0, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(new com.example.starter.cosmetic.child.web.dto.ChildSafetyReportRequest(
                        "SR-C-6", "BAND_0_36", 0, 12, "仅覆盖0~12月")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        assertThat(result.filing().currentRevision().rejectReason())
                .contains("CHILD_REPORT_COVERAGE").contains("[0,36]");
    }

    @Test
    void concentrationJudgedByStricterBandLimitAcrossBands() {
        // 跨档 0~144 月：苯氧乙醇两档限值 0.4%/1.0%，按较严 0.4% 判定，0.5% 超限
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-7", "PC-CONC-OVER",
                "合成儿童乳", 0, 144, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.phenoxyethanolFormulation("0.5000"),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-7"),
                        ChildFilingFixtures.highBandReport("SR-H-7")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        assertThat(result.filing().currentRevision().rejectReason())
                .contains("CHILD_CONCENTRATION").contains("0.4");
    }

    @Test
    void concentrationExactlyAtStricterLimitPasses() {
        // 恰等较严限值 0.4% 合格
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-8", "PC-CONC-EQ",
                "合成儿童乳", 0, 144, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.phenoxyethanolFormulation("0.4000"),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-8"),
                        ChildFilingFixtures.highBandReport("SR-H-8")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);

        ChildFilingResultResponse result = childFilingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.IN_PUBLICITY.name());
        assertThat(result.evidence())
                .anyMatch(d -> d.ruleCode().equals("CHILD_CONCENTRATION") && d.passed());
    }

    @Test
    void invalidAgeRangeRejectedAsBadArgument() {
        ChildSubmitRequest request = new ChildSubmitRequest("REQ-C-9", "PC-BAD-AGE",
                "合成乳", 37, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(), List.of(), List.of(), null, null);

        assertThatThrownBy(() -> childFilingService.submit(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0≤下限≤上限≤144");
    }

    @Test
    void duplicateProductSubmitRejected422() {
        childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-10", "PC-DUP"));

        assertThatThrownBy(() -> childFilingService.submit(
                ChildFilingFixtures.lowBandSubmit("REQ-C-11", "PC-DUP")))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_FILING_EXISTS"));
    }

    // ============================ 补正与公示 ============================

    @Test
    void correctionGeneratesNewRevisionAndInvalidatesOldPendingApproval() {
        // 首次提交缺高龄档材料被驳回
        ChildSubmitRequest rejected = new ChildSubmitRequest("REQ-C-12", "PC-CORR-1",
                "合成儿童乳", 0, 144, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-12")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);
        ChildFilingResultResponse first = childFilingService.submit(rejected);
        assertThat(first.filing().status()).isEqualTo(ChildFilingStatus.REVIEW_REJECTED.name());
        String filingNo = first.filing().filingNo();

        // 补正：两档材料齐备
        ChildCorrectionRequest correction = new ChildCorrectionRequest("REQ-C-13", 1,
                0, 144, List.of("CH_MOISTURIZE"), ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-13"),
                        ChildFilingFixtures.highBandReport("SR-H-13")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);
        ChildFilingResultResponse corrected = childFilingService.correct(filingNo, correction);

        assertThat(corrected.filing().versionNo()).isEqualTo(2);
        assertThat(corrected.filing().status()).isEqualTo(ChildFilingStatus.IN_PUBLICITY.name());
        assertThat(corrected.filing().currentRevisionNo()).isEqualTo(2);
        assertThat(corrected.filing().currentRevision().revisionType()).isEqualTo("CORRECTION");
        assertThat(corrected.filing().currentRevision().status())
                .isEqualTo(ChildRevisionStatus.PUBLIC.name());
        // 旧修订待审批准失效
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.INVALIDATED);
    }

    @Test
    void publicityExpiryEffectuatesOnlyLatestRevision() {
        ChildSubmitRequest rejected = new ChildSubmitRequest("REQ-C-14", "PC-CORR-2",
                "合成儿童乳", 0, 144, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-14")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);
        childFilingService.submit(rejected);
        ChildCorrectionRequest correction = new ChildCorrectionRequest("REQ-C-15", 1,
                0, 144, List.of("CH_MOISTURIZE"), ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-L-15"),
                        ChildFilingFixtures.highBandReport("SR-H-15")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);
        childFilingService.correct("SYN-C-000001", correction);

        clock.setInstant(EXPIRES_EXPECTED);
        int effected = childFilingService.expireDuePublicities(null);

        assertThat(effected).isEqualTo(1);
        assertThat(childFilingPort.findRevision("SYN-C-000001", 2).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE);
        // 旧修订不会被误生效：保持已失效
        assertThat(childFilingPort.findRevision("SYN-C-000001", 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.INVALIDATED);
        assertThat(childFilingPort.findFiling("SYN-C-000001").orElseThrow().status())
                .isEqualTo(ChildFilingStatus.EFFECTIVE);
    }

    @Test
    void correctionOnEffectiveFilingRejected() {
        childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-16", "PC-CORR-3"));
        clock.setInstant(EXPIRES_EXPECTED);
        childFilingService.expireDuePublicities(null);

        ChildCorrectionRequest correction = ChildFilingFixtures.correctionOf("REQ-C-17", 1,
                ChildFilingFixtures.lowBandSubmit("REQ-X", "PC-CORR-3"));
        assertThatThrownBy(() -> childFilingService.correct("SYN-C-000001", correction))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("CORRECTION_NOT_ALLOWED"));
    }

    // ============================ 变更与版本门禁 ============================

    private String effectiveLowBandFiling(String requestId, String productCode) {
        ChildFilingResultResponse submitted =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit(requestId, productCode));
        clock.setInstant(EXPIRES_EXPECTED);
        childFilingService.expireDuePublicities(null);
        return submitted.filing().filingNo();
    }

    @Test
    void simplePackagingChangeEffectiveImmediatelyAndSupersedesOldRevision() {
        String filingNo = effectiveLowBandFiling("REQ-C-18", "PC-SIMPLE-1");

        ChildChangeRequest change = new ChildChangeRequest("REQ-C-19", 1, "SIMPLE",
                null, null, null, null, null, null, null, "新包装：请置于阴凉处", null);
        ChildFilingResultResponse result = childFilingService.change(filingNo, change);

        assertThat(result.filing().versionNo()).isEqualTo(2);
        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.EFFECTIVE.name());
        assertThat(result.filing().currentRevision().revisionType()).isEqualTo("SIMPLE");
        assertThat(result.filing().currentRevision().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE.name());
        assertThat(result.filing().currentRevision().packagingText()).contains("新包装");
        // 变更生效与旧备案失效同事务：旧修订已 SUPERSEDED
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.SUPERSEDED);
    }

    @Test
    void disguisedSimpleChangeOnAgeRangeRejectedByGate() {
        String filingNo = effectiveLowBandFiling("REQ-C-20", "PC-GATE-1");

        // 声明简易变更，但实际把年龄上限从 36 改为 144：伪装简易变更，业务拒绝
        ChildChangeRequest disguised = new ChildChangeRequest("REQ-C-21", 1, "SIMPLE",
                null, null, 144, null, null, null, null, "仅改包装", null);
        assertThatThrownBy(() -> childFilingService.change(filingNo, disguised))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_CHANGE_MUST_BE_SUBSTANTIVE"));

        // 标识适用范围（标识文本）变化同样必须实质变更
        ChildChangeRequest labelDisguised = new ChildChangeRequest("REQ-C-22", 1, "SIMPLE",
                null, null, null, null, null, null,
                List.of(new ChildLabelRequest("BAND_0_36", "适用于0~3岁（改写）")), null, null);
        assertThatThrownBy(() -> childFilingService.change(filingNo, labelDisguised))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_CHANGE_MUST_BE_SUBSTANTIVE"));
    }

    @Test
    void substantiveAgeChangeReReviewsAndSwitchesAtomicallyOnExpiry() {
        String filingNo = effectiveLowBandFiling("REQ-C-23", "PC-SUBST-1");

        // 实质变更：年龄范围扩为 0~144 月，两档材料齐备
        ChildChangeRequest change = new ChildChangeRequest("REQ-C-24", 1, "SUBSTANTIVE",
                null, 0, 144, null, null,
                List.of(ChildFilingFixtures.lowBandReport("SR-L-24"),
                        ChildFilingFixtures.highBandReport("SR-H-24")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);
        ChildFilingResultResponse changed = childFilingService.change(filingNo, change);

        assertThat(changed.filing().versionNo()).isEqualTo(2);
        assertThat(changed.filing().status()).isEqualTo(ChildFilingStatus.IN_PUBLICITY.name());
        assertThat(changed.filing().currentRevision().revisionType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().currentRevision().ageUpperMonths()).isEqualTo(144);
        // 新修订公示期间旧修订仍生效
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE);

        // 公示期满：新修订生效与旧修订失效原子完成
        Instant newExpires = changed.filing().currentRevision().publicExpiresAtUtc();
        clock.setInstant(newExpires);
        assertThat(childFilingService.expireDuePublicities(null)).isEqualTo(1);

        assertThat(childFilingPort.findRevision(filingNo, 2).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE);
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.SUPERSEDED);
        assertThat(childFilingPort.findFiling(filingNo).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.EFFECTIVE);
    }

    @Test
    void substantiveChangeFailingReviewKeepsOldRevisionEffective() {
        String filingNo = effectiveLowBandFiling("REQ-C-25", "PC-SUBST-2");

        // 实质变更扩龄但缺高龄档安全报告：零生效，旧备案保持生效
        ChildChangeRequest change = new ChildChangeRequest("REQ-C-26", 1, "SUBSTANTIVE",
                null, 0, 144, null, null,
                List.of(ChildFilingFixtures.lowBandReport("SR-L-26")),
                List.of(ChildFilingFixtures.lowBandLabel(), ChildFilingFixtures.highBandLabel()),
                null, null);
        ChildFilingResultResponse result = childFilingService.change(filingNo, change);

        assertThat(result.filing().currentRevision().status())
                .isEqualTo(ChildRevisionStatus.REJECTED.name());
        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.EFFECTIVE.name());
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE);
    }

    @Test
    void changeOnNonEffectiveFilingRejected() {
        ChildFilingResultResponse submitted =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-27", "PC-NOT-EFF"));

        ChildChangeRequest change = new ChildChangeRequest("REQ-C-28", 1, "SIMPLE",
                null, null, null, null, null, null, null, "新包装", null);
        assertThatThrownBy(() -> childFilingService.change(submitted.filing().filingNo(), change))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("FILING_NOT_EFFECTIVE"));
    }

    @Test
    void changeWithoutAnyDifferenceRejected() {
        String filingNo = effectiveLowBandFiling("REQ-C-29", "PC-NOCHANGE");

        ChildChangeRequest noop = new ChildChangeRequest("REQ-C-30", 1, "SIMPLE",
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> childFilingService.change(filingNo, noop))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("NO_CHANGE"));
    }

    // ============================ 版本与幂等 ============================

    @Test
    void versionMismatchRejected422AndFailureDoesNotOccupyIdempotencyKey() {
        String filingNo = effectiveLowBandFiling("REQ-C-31", "PC-VERSION");

        // expectedVersion 不一致：业务拒绝 422，且失败不占键
        ChildChangeRequest stale = new ChildChangeRequest("REQ-C-32", 99, "SIMPLE",
                null, null, null, null, null, null, null, "新包装A", null);
        assertThatThrownBy(() -> childFilingService.change(filingNo, stale))
                .isInstanceOfSatisfying(BusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_MISMATCH"));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id = 'REQ-C-32'",
                Integer.class);
        assertThat(idemCount).isZero();

        // 同一 requestId 修正版本后重试成功（键未被失败占用）
        ChildChangeRequest fixed = new ChildChangeRequest("REQ-C-32", 1, "SIMPLE",
                null, null, null, null, null, null, null, "新包装A", null);
        ChildFilingResultResponse result = childFilingService.change(filingNo, fixed);
        assertThat(result.filing().versionNo()).isEqualTo(2);
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstFrozenResult() {
        ChildFilingResultResponse first =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-33", "PC-IDEM-1"));
        ChildFilingResultResponse replay =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-33", "PC-IDEM-1"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.filing().filingNo()).isEqualTo(first.filing().filingNo());
        assertThat(replay.atUtc()).isEqualTo(first.atUtc());
        // 重放返回首次冻结结果（含冻结的规则版本与标识摘要）
        assertThat(replay.filing().currentRevision().labelDigest())
                .isEqualTo(first.filing().currentRevision().labelDigest());
        assertThat(childFilingPort.findRevisions(first.filing().filingNo())).hasSize(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturnsConflict409() {
        childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-34", "PC-IDEM-2"));

        ChildSubmitRequest changed = new ChildSubmitRequest("REQ-C-34", "PC-IDEM-2",
                "改名产品", 0, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-34")),
                List.of(ChildFilingFixtures.lowBandLabel()), null, null);
        assertThatThrownBy(() -> childFilingService.submit(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("409");
    }

    @Test
    void fingerprintDistinguishesSeparatorInsideLabelText() {
        // 单条标识文本含分隔符 "A|B"
        ChildSubmitRequest first = new ChildSubmitRequest("REQ-C-35", "PC-FP",
                "合成乳", 0, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-35")),
                List.of(new ChildLabelRequest("BAND_0_36", "A|B")), null, null);
        childFilingService.submit(first);

        // 同键但两条标识 "A"、"B"：结构化指纹须区分，判为异参 409
        ChildSubmitRequest other = new ChildSubmitRequest("REQ-C-35", "PC-FP",
                "合成乳", 0, 36, List.of("CH_MOISTURIZE"),
                ChildFilingFixtures.gentleFormulation(),
                List.of(ChildFilingFixtures.lowBandReport("SR-C-35")),
                List.of(new ChildLabelRequest("BAND_0_36", "A"),
                        new ChildLabelRequest("BAND_0_36", "B")), null, null);
        assertThatThrownBy(() -> childFilingService.submit(other))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("409");
    }

    // ============================ 查询 ============================

    @Test
    void evidenceQueryReturnsChildReviewDetailsPerRevision() {
        ChildFilingResultResponse submitted =
                childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-C-36", "PC-EVID"));
        String filingNo = submitted.filing().filingNo();

        List<ReviewDetailResponse> evidence = childFilingService.getEvidence(filingNo, 1);
        assertThat(evidence)
                .allMatch(d -> d.stage().equals("CHILD_REVIEW"))
                .extracting(ReviewDetailResponse::ruleCode)
                .contains("CHILD_CLAIM_CATALOG", "CHILD_SAFETY_REPORT_PRESENT",
                        "CHILD_LABEL_PRESENT", "CHILD_REPORT_COVERAGE", "CHILD_CONCENTRATION");
    }

    @Test
    void revisionsAndAgeRulesAreQueryable() {
        ChildFilingResultResponse submitted =
                childFilingService.submit(ChildFilingFixtures.crossBandSubmit("REQ-C-37", "PC-Q"));

        assertThat(childFilingService.getRevisions(submitted.filing().filingNo())).hasSize(1);
        AgeRuleResponse rules = childFilingService.getAgeRules();
        assertThat(rules.ruleVersion()).isEqualTo("CHILD-AGE-2026A");
        assertThat(rules.bands()).hasSize(2);
        assertThat(rules.restrictedLimits()).isNotEmpty();
        assertThat(rules.childEfficacies()).isNotEmpty();
    }

    @Test
    void unknownFilingReturnsNotFound() {
        assertThatThrownBy(() -> childFilingService.getFiling("SYN-C-999999"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> childFilingService.getEvidence("SYN-C-999999", null))
                .isInstanceOf(NotFoundException.class);
    }
}
