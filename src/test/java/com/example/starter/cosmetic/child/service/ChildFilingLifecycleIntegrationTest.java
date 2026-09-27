package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.testsupport.ChildFilingFixtures;
import com.example.starter.cosmetic.child.web.dto.ChildChangeFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 儿童分龄备案主生命周期 H2 集成测试：
 * 单档/跨档受理冻结、缺项一次列全零生效、补正同号新修订并失效旧修订、
 * 公示仅最新修订、期满原子生效占槽、实质/简易变更、expectedVersion 与 400/404/409/422。
 */
class ChildFilingLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildFilingService childService;

    @Autowired
    private ChildFilingPort childPort;

    private static final Instant EXPIRES = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void singleTierSubmissionFreezesReportsRuleVersionAndLabelDigestAndEntersPublicity() {
        ChildFilingResultResponse result =
                childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-1", "CP-1"));

        ChildFilingResponse f = result.filing();
        assertThat(f.status()).isEqualTo(ChildFilingStatus.PUBLIC.name());
        assertThat(f.filingNo()).isEqualTo("SYN-CF-000001");
        assertThat(f.versionNo()).isEqualTo(1);
        assertThat(f.changeType()).isEqualTo("INITIAL");
        assertThat(f.requiredTiers()).containsExactly("TIER_0_36");
        assertThat(f.ageFromMonths()).isZero();
        assertThat(f.ageToMonths()).isEqualTo(36);
        // 受理冻结：分龄规则版本、标识摘要、分档独立报告清单
        assertThat(f.ageRuleVersion()).isEqualTo("CHILD-AGE-RULE-V1");
        assertThat(f.ageLabelDigest()).hasSize(64).matches("[0-9a-f]+");
        assertThat(f.safetyReports()).hasSize(1);
        assertThat(f.safetyReports().get(0).tierCode()).isEqualTo("TIER_0_36");
        assertThat(f.publicNoticeDateUtc()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(f.publicExpiresAtUtc()).isEqualTo(EXPIRES);
        assertThat(childPort.findActiveSlot("CP-1")).isEmpty();
    }

    @Test
    void missingOneTierReportIsRejectedListsAllGapsAndTakesNoEffect() {
        ChildFilingResultResponse result =
                childService.submit(ChildFilingFixtures.crossTierMissingOldReport("REQ-C-2", "CP-2"));

        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.FORM_REJECTED.name());
        assertThat(result.filing().rejectReason())
                .contains("TIER_SAFETY_REPORT_PRESENT")
                .contains("TIER_37_144");
        // 零生效：无有效槽，无公示期
        assertThat(childPort.findActiveSlot("CP-2")).isEmpty();
        assertThat(result.filing().publicExpiresAtUtc()).isNull();
    }

    @Test
    void correctionCreatesNewRevisionVoidsOldAndOnlyLatestRevisionGoesPublicThenEffective() {
        // 首次跨档缺大龄报告 → 驳回
        ChildFilingResultResponse rejected = childService.submit(
                ChildFilingFixtures.crossTierMissingOldReport("REQ-C-3", "CP-3"));
        String no = rejected.filing().filingNo();
        long v1Id = childPort.findLatestByNo(no).orElseThrow().id();

        // 错误 expectedVersion → 409
        ChildCorrectFilingRequest wrongVersion =
                ChildFilingFixtures.crossTierCorrection("REQ-C-3B", 99);
        assertThatThrownBy(() -> childService.correct(no, wrongVersion))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_VERSION_MISMATCH"));

        // 补正：同号 version 2，旧修订 v1 原子 VOID
        ChildFilingResultResponse corrected =
                childService.correct(no, ChildFilingFixtures.crossTierCorrection("REQ-C-3C", 1));

        assertThat(corrected.filing().filingNo()).isEqualTo(no);
        assertThat(corrected.filing().versionNo()).isEqualTo(2);
        assertThat(corrected.filing().changeType()).isEqualTo("CORRECTION");
        assertThat(corrected.filing().status()).isEqualTo(ChildFilingStatus.PUBLIC.name());
        assertThat(corrected.filing().requiredTiers())
                .containsExactly("TIER_0_36", "TIER_37_144");
        assertThat(childPort.findVersion(no, 1).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.VOID);
        assertThat(childPort.findVersion(no, 1).orElseThrow().voidedAtUtc()).isNotNull();

        // 期满：仅最新修订 v2 生效占槽
        clock.setInstant(EXPIRES);
        assertThat(childService.expireDuePublicNotices(null)).isEqualTo(1);
        assertThat(childPort.findVersion(no, 2).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.EFFECTIVE);
        assertThat(childPort.findActiveSlot("CP-3").orElseThrow().versionNo()).isEqualTo(2);
        // v1 仍是 VOID，不会被错误生效
        assertThat(childPort.findById(v1Id).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.VOID);
    }

    @Test
    void correctionIsIdempotentSameKeySameParamsReplaysNewRevision() {
        ChildFilingResultResponse rejected = childService.submit(
                ChildFilingFixtures.crossTierMissingOldReport("REQ-C-4", "CP-4"));
        String no = rejected.filing().filingNo();

        ChildCorrectFilingRequest correction = ChildFilingFixtures.crossTierCorrection("REQ-C-4C", 1);
        ChildFilingResultResponse first = childService.correct(no, correction);
        ChildFilingResultResponse replay = childService.correct(no, correction);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.filing().versionNo()).isEqualTo(first.filing().versionNo());
        assertThat(childPort.findByProduct("CP-4")).hasSize(2); // v1 + v2，重放不新增
    }

    @Test
    void correctionSameKeyDifferentParamsConflicts() {
        ChildFilingResultResponse rejected = childService.submit(
                ChildFilingFixtures.crossTierMissingOldReport("REQ-C-5", "CP-5"));
        String no = rejected.filing().filingNo();

        ChildCorrectFilingRequest first = ChildFilingFixtures.crossTierCorrection("REQ-C-5C", 1);
        childService.correct(no, first);

        // 同键异参（expectedVersion 变为 2）→ 409
        ChildCorrectFilingRequest different = ChildFilingFixtures.crossTierCorrection("REQ-C-5C", 2);
        assertThatThrownBy(() -> childService.correct(no, different))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("409");
    }

    @Test
    void correctionAfterEffectiveIsBusinessRejected422() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-6", "CP-6"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        ChildCorrectFilingRequest correction = ChildFilingFixtures.crossTierCorrection("REQ-C-6C", 1);
        assertThatThrownBy(() -> childService.correct("SYN-CF-000001", correction))
                .isInstanceOfSatisfying(ChildBusinessRejectionException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_CORRECTION_NOT_ALLOWED"));
    }

    @Test
    void failedBusinessOperationDoesNotConsumeIdempotencyKey() {
        // 首次生效后对生效件补正（422），键不应被占用：改对状态前同键可被另一合法操作语义复用验证
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-7", "CP-7"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        ChildCorrectFilingRequest correction = ChildFilingFixtures.crossTierCorrection("REQ-C-7C", 1);
        assertThatThrownBy(() -> childService.correct("SYN-CF-000001", correction))
                .isInstanceOf(ChildBusinessRejectionException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM child_idempotency_record WHERE request_id = ?",
                Integer.class, "REQ-C-7C");
        assertThat(rows).isZero();
    }

    @Test
    void ageOrLabelChangeOnEffectiveFilingIsForcedSubstantiveWithNewFilingNo() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-8", "CP-8"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        // 改变年龄区间（0-36 → 0-144）并补两档材料：必须实质变更，即使同时给包装文字也不得走简易
        List<FormulationItemRequest> formulation = ChildFilingFixtures.waterFormulation();
        ChildChangeFilingRequest change = new ChildChangeFilingRequest(
                "REQ-C-8C", ChildFilingFixtures.AT, 1, null, 0, 144, formulation, List.of("CHILD_MOISTURIZING"),
                List.of(ChildFilingFixtures.youngReport("CR-Y8", 0, 36, true),
                        ChildFilingFixtures.oldReport("CR-O8", 37, 144, true)),
                "适用年龄 0-144 月（出生至144月龄）", "包装文字同时调整");
        ChildFilingResultResponse changed = childService.change("CP-8", change);

        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-CF-000002");
        assertThat(changed.filing().parentFilingNo()).isEqualTo("SYN-CF-000001");
        assertThat(changed.filing().status()).isEqualTo(ChildFilingStatus.PUBLIC.name());
        assertThat(changed.filing().requiredTiers())
                .containsExactly("TIER_0_36", "TIER_37_144");
        // 公示期间旧备案仍有效占槽
        assertThat(childPort.findActiveSlot("CP-8").orElseThrow().filingNo()).isEqualTo("SYN-CF-000001");

        // 新版本期满：同事务新版本生效 + 旧版本 SUPERSEDED + 换槽
        clock.setInstant(changed.filing().publicExpiresAtUtc());
        childService.expireDuePublicNotices(null);
        assertThat(childPort.findVersion("SYN-CF-000002", 2).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.EFFECTIVE);
        assertThat(childPort.findVersion("SYN-CF-000001", 1).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.SUPERSEDED);
        assertThat(childPort.findActiveSlot("CP-8").orElseThrow().filingNo()).isEqualTo("SYN-CF-000002");
    }

    @Test
    void labelScopeChangeAloneIsSubstantive() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-9", "CP-9"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        // 仅改标识适用范围（年龄不变，配方宣称不变）：仍须实质变更
        ChildChangeFilingRequest change = new ChildChangeFilingRequest(
                "REQ-C-9C", ChildFilingFixtures.AT, 1, null, null, null, null, null, null,
                "适用年龄 0-24 月（缩小标识范围）", null);
        // 注意：标识范围与实际年龄[0,36]不一致会形式驳回，但 changeType 仍应为 SUBSTANTIVE
        ChildFilingResultResponse changed = childService.change("CP-9", change);
        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-CF-000002");
    }

    @Test
    void simplePackagingChangeKeepsNumberAndSwitchesAtomically() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-10", "CP-10"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        ChildChangeFilingRequest change =
                ChildFilingFixtures.packagingChange("REQ-C-10C", 1, "新包装：置儿童不易触及处");
        ChildFilingResultResponse result = childService.change("CP-10", change);

        assertThat(result.filing().changeType()).isEqualTo("SIMPLE");
        assertThat(result.filing().filingNo()).isEqualTo("SYN-CF-000001");
        assertThat(result.filing().versionNo()).isEqualTo(2);
        assertThat(result.filing().status()).isEqualTo(ChildFilingStatus.EFFECTIVE.name());
        assertThat(childPort.findVersion("SYN-CF-000001", 1).orElseThrow().status())
                .isEqualTo(ChildFilingStatus.SUPERSEDED);
        assertThat(childPort.findActiveSlot("CP-10").orElseThrow().versionNo()).isEqualTo(2);
    }

    @Test
    void staleExpectedVersionOnChangeConflicts() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-11", "CP-11"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);
        // 先做一次简易变更使有效版本变为 2
        childService.change("CP-11", ChildFilingFixtures.packagingChange("REQ-C-11A", 1, "包装A"));

        // 再用过期的 expectedVersion=1 → 409
        ChildChangeFilingRequest stale =
                ChildFilingFixtures.packagingChange("REQ-C-11B", 1, "包装B");
        assertThatThrownBy(() -> childService.change("CP-11", stale))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_VERSION_MISMATCH"));
    }

    @Test
    void crossTierConcentrationStrictestEnforcedInH2() {
        // 跨档含水杨酸恰等较严限值 0.5% → 通过
        List<FormulationItemRequest> atLimit = List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("79.5000")),
                new FormulationItemRequest("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5000")));
        ChildSubmitFilingRequest pass = new ChildSubmitFilingRequest(
                "REQ-C-12", ChildFilingFixtures.AT, "CP-12", "跨档水杨酸儿童霜", 0, 144, atLimit,
                List.of("CHILD_MOISTURIZING"),
                List.of(ChildFilingFixtures.youngReport("CR-Y12", 0, 36, true),
                        ChildFilingFixtures.oldReport("CR-O12", 37, 144, true)),
                "适用年龄 0-144 月");
        assertThat(childService.submit(pass).filing().status())
                .isEqualTo(ChildFilingStatus.PUBLIC.name());

        // 超过较严限值 0.5%（即使低于大龄档 1.0%）→ 驳回
        List<FormulationItemRequest> over = List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("79.4999")),
                new FormulationItemRequest("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5001")));
        ChildSubmitFilingRequest fail = new ChildSubmitFilingRequest(
                "REQ-C-13", ChildFilingFixtures.AT, "CP-13", "跨档水杨酸儿童霜", 0, 144, over,
                List.of("CHILD_MOISTURIZING"),
                List.of(ChildFilingFixtures.youngReport("CR-Y13", 0, 36, true),
                        ChildFilingFixtures.oldReport("CR-O13", 37, 144, true)),
                "适用年龄 0-144 月");
        ChildFilingResultResponse rejected = childService.submit(fail);
        assertThat(rejected.filing().status()).isEqualTo(ChildFilingStatus.FORM_REJECTED.name());
        assertThat(rejected.filing().rejectReason()).contains("CHILD_CONCENTRATION");
    }

    @Test
    void invalidAgeRangeReturns400AndNotFound404() {
        ChildSubmitFilingRequest bad = new ChildSubmitFilingRequest(
                "REQ-C-14", ChildFilingFixtures.AT, "CP-14", "x", 100, 50, ChildFilingFixtures.waterFormulation(),
                List.of("CHILD_MOISTURIZING"),
                List.of(ChildFilingFixtures.youngReport("CR14", 0, 36, true)), "适用年龄 50-100 月");
        assertThatThrownBy(() -> childService.submit(bad))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> childService.getFiling("SYN-CF-NOPE"))
                .isInstanceOf(com.example.starter.cosmetic.service.NotFoundException.class);
    }

    @Test
    void reviewEvidenceAndRuleAreQueryable() {
        childService.submit(ChildFilingFixtures.youngSubmit("REQ-C-15", "CP-15"));

        List<com.example.starter.cosmetic.child.web.dto.ChildReviewDetailResponse> reviews =
                childService.getReviews("SYN-CF-000001", 1);
        assertThat(reviews).extracting("ruleCode")
                .contains("AGE_RANGE_VALID", "REQUIRED_TIERS", "CHILD_CLAIM_CATALOG",
                        "TIER_SAFETY_REPORT_PRESENT", "TIER_REPORT_COVERAGE",
                        "AGE_LABEL", "CHILD_CONCENTRATION");

        ChildRuleResponse rule = childService.getRule();
        assertThat(rule.ruleVersion()).isEqualTo("CHILD-AGE-RULE-V1");
        assertThat(rule.tiers()).hasSize(2);
        assertThat(rule.limits().get("TIER_0_36").get("SALICYLIC_ACID")).isEqualByComparingTo("0.5000");
        assertThat(rule.limits().get("TIER_37_144").get("SALICYLIC_ACID")).isEqualByComparingTo("1.0000");
    }
}
