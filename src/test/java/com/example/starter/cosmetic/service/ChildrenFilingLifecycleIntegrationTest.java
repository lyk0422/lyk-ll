package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.ChildrenFilingStatus;
import com.example.starter.cosmetic.port.ChildrenFilingPort;
import com.example.starter.cosmetic.testsupport.ChildrenFixtures;
import com.example.starter.cosmetic.web.dto.AgeLabelRequest;
import com.example.starter.cosmetic.web.dto.ChangeChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildrenEvidenceResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResultResponse;
import com.example.starter.cosmetic.web.dto.CorrectChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.SubmitChildrenFilingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 儿童备案主生命周期 H2 集成测试：分龄审查、受理冻结、材料补正（新修订/旧修订失效）、
 * 实质/简易变更、版本递增与 expectedVersion、幂等（重放/异参409/失败不占键）、判定证据查询。
 */
class ChildrenFilingLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildrenFilingService childrenFilingService;

    @Autowired
    private ChildrenFilingPort childrenFilingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void crossBandSubmissionPassesAndFreezesAcceptanceData() {
        ChildrenFilingResultResponse result =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-1", "P-C-1"));

        assertThat(result.filing().status()).isEqualTo(ChildrenFilingStatus.PUBLIC.name());
        assertThat(result.filing().filingNo()).isEqualTo("SYN-C-000001");
        assertThat(result.filing().versionNo()).isEqualTo(1);
        assertThat(result.filing().revisionNo()).isEqualTo(1);
        assertThat(result.filing().publicNoticeDateUtc()).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
        assertThat(result.filing().publicExpiresAtUtc()).isEqualTo(EXPIRES_EXPECTED);
        // 受理冻结：报告清单、分龄规则版本、标识文本摘要
        assertThat(result.filing().safetyReports()).hasSize(2);
        assertThat(result.filing().ruleVersion()).isEqualTo(1);
        assertThat(result.filing().labelDigest()).isNotBlank();
        assertThat(result.reviewDetails()).isNotEmpty();
        assertThat(childrenFilingPort.findActiveSlot("P-C-1")).isEmpty();
    }

    @Test
    void missingBandMaterialsRejected422ListingAllAndZeroEffectAndKeyReusable() {
        // 跨档 0~144 但只备婴幼儿档资料：两档资料都齐才受理，缺项一次列全
        SubmitChildrenFilingRequest incomplete = new SubmitChildrenFilingRequest(
                "REQ-C-2", "P-C-2", "合成儿童霜", 0, 144,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-2")),
                List.of(ChildrenFixtures.infantLabel("适用于0~36月")),
                List.of());

        assertThatThrownBy(() -> childrenFilingService.submit(incomplete))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> {
                    assertThat(e.code()).isEqualTo("CHILD_REVIEW_REJECTED");
                    assertThat(e.problems()).anyMatch(p -> p.contains("BAND_SAFETY_REPORT"));
                    assertThat(e.problems()).anyMatch(p -> p.contains("BAND_AGE_LABEL"));
                });
        // 零生效：无备案行、无有效槽
        assertThat(childrenFilingPort.findByProduct("P-C-2")).isEmpty();
        assertThat(childrenFilingPort.findActiveSlot("P-C-2")).isEmpty();

        // 失败不占键：同一 requestId 补齐材料后可成功
        SubmitChildrenFilingRequest completed = new SubmitChildrenFilingRequest(
                "REQ-C-2", "P-C-2", "合成儿童霜", 0, 144,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-2"),
                        ChildrenFixtures.childSafetyReport("SR-C-2")),
                List.of(ChildrenFixtures.infantLabel("适用于0~36月"),
                        ChildrenFixtures.childLabel("适用于37~144月")),
                List.of());
        ChildrenFilingResultResponse ok = childrenFilingService.submit(completed);
        assertThat(ok.filing().status()).isEqualTo(ChildrenFilingStatus.PUBLIC.name());
    }

    @Test
    void adultReportsCannotSubstituteChildrenSafetyReport() {
        SubmitChildrenFilingRequest withAdultReport = new SubmitChildrenFilingRequest(
                "REQ-C-3", "P-C-3", "合成婴幼儿霜", 0, 36,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(),
                List.of(ChildrenFixtures.infantLabel("适用于0~36月")),
                List.of(ChildrenFixtures.adultReport("AR-3")));

        assertThatThrownBy(() -> childrenFilingService.submit(withAdultReport))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.problems())
                                .anyMatch(p -> p.contains("不能替代儿童安全报告")));
        assertThat(childrenFilingPort.findByProduct("P-C-3")).isEmpty();
    }

    @Test
    void concentrationJudgedByStricterBandLimitAndExactLimitPasses() {
        // 跨档较严限值 0.4，恰等合格
        SubmitChildrenFilingRequest atLimit = new SubmitChildrenFilingRequest(
                "REQ-C-4", "P-C-4", "合成儿童霜", 0, 144,
                ChildrenFixtures.phenoxyethanolFormulation("0.4000"), List.of("MOISTURIZING"),
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-4"),
                        ChildrenFixtures.childSafetyReport("SR-C-4")),
                List.of(ChildrenFixtures.infantLabel("0~36月"), ChildrenFixtures.childLabel("37~144月")),
                List.of());
        assertThat(childrenFilingService.submit(atLimit).filing().status())
                .isEqualTo(ChildrenFilingStatus.PUBLIC.name());

        // 0.6 超过较严档 0.4 → 422
        SubmitChildrenFilingRequest overLimit = new SubmitChildrenFilingRequest(
                "REQ-C-5", "P-C-5", "合成儿童霜", 0, 144,
                ChildrenFixtures.phenoxyethanolFormulation("0.6000"), List.of("MOISTURIZING"),
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-5"),
                        ChildrenFixtures.childSafetyReport("SR-C-5")),
                List.of(ChildrenFixtures.infantLabel("0~36月"), ChildrenFixtures.childLabel("37~144月")),
                List.of());
        assertThatThrownBy(() -> childrenFilingService.submit(overLimit))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.problems()).anyMatch(p -> p.contains("CHILD_CONCENTRATION")));
    }

    @Test
    void invalidAgeRangeRejectedAs400() {
        SubmitChildrenFilingRequest inverted = new SubmitChildrenFilingRequest(
                "REQ-C-6", "P-C-6", "合成儿童霜", 40, 36,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> childrenFilingService.submit(inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("申请年龄范围非法");

        SubmitChildrenFilingRequest tooOld = new SubmitChildrenFilingRequest(
                "REQ-C-7", "P-C-7", "合成儿童霜", 0, 200,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> childrenFilingService.submit(tooOld))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctionCreatesNewRevisionInvalidatesOldAndPublicityUsesLatest() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-8", "P-C-8"));
        String filingNo = submitted.filing().filingNo();

        // 材料补正：更换安全报告清单与标识文本（范围不变）
        CorrectChildrenFilingRequest correction = new CorrectChildrenFilingRequest(
                "REQ-C-8C", 1, null, null, null, null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-8B"),
                        ChildrenFixtures.childSafetyReport("SR-C-8B")),
                List.of(ChildrenFixtures.infantLabel("新标识0~36月"),
                        ChildrenFixtures.childLabel("新标识37~144月")));
        ChildrenFilingResultResponse corrected = childrenFilingService.correct(filingNo, correction);

        assertThat(corrected.filing().versionNo()).isEqualTo(2);
        assertThat(corrected.filing().revisionNo()).isEqualTo(2);
        assertThat(corrected.filing().status()).isEqualTo(ChildrenFilingStatus.PUBLIC.name());
        assertThat(corrected.filing().labelDigest())
                .isNotEqualTo(submitted.filing().labelDigest());

        // 旧修订待审批准失效；公示只使用最新修订
        assertThat(childrenFilingPort.findVersion(filingNo, 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.REVISION_SUPERSEDED);
        clock.setInstant(EXPIRES_EXPECTED);
        assertThat(childrenFilingService.expireDuePublicNotices(null)).isEqualTo(1);
        assertThat(childrenFilingPort.findVersion(filingNo, 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.REVISION_SUPERSEDED);
        assertThat(childrenFilingPort.findVersion(filingNo, 2, 2).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.EFFECTIVE);
        assertThat(childrenFilingPort.findActiveSlot("P-C-8").orElseThrow().versionNo()).isEqualTo(2);
    }

    @Test
    void correctionWithSubstantiveFieldsRejected422() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-9", "P-C-9"));
        String filingNo = submitted.filing().filingNo();

        // 补正中夹带年龄范围改变 → 422 须走实质变更
        CorrectChildrenFilingRequest ageChange = new CorrectChildrenFilingRequest(
                "REQ-C-9A", 1, null, null, null, 0, 100, null, null);
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, ageChange))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("SUBSTANTIVE_CHANGE_REQUIRED"));

        // 补正中夹带标识适用范围改变 → 422
        CorrectChildrenFilingRequest labelRangeChange = new CorrectChildrenFilingRequest(
                "REQ-C-9B", 1, null, null, null, null, null, null,
                List.of(new AgeLabelRequest("INFANT", "标识", 0, 24),
                        ChildrenFixtures.childLabel("37~144月")));
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, labelRangeChange))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("SUBSTANTIVE_CHANGE_REQUIRED"));

        // 补正中夹带宣称改变 → 422
        CorrectChildrenFilingRequest claimsChange = new CorrectChildrenFilingRequest(
                "REQ-C-9C", 1, null, null, List.of("SUNSCREEN"), null, null, null, null);
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, claimsChange))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("SUBSTANTIVE_CHANGE_REQUIRED"));

        // 旧修订仍在公示中（失败零生效）
        assertThat(childrenFilingPort.findVersion(filingNo, 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.PUBLIC);
    }

    @Test
    void correctionAfterExpiryRejected422() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-10", "P-C-10"));
        String filingNo = submitted.filing().filingNo();
        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        CorrectChildrenFilingRequest correction = new CorrectChildrenFilingRequest(
                "REQ-C-10C", 1, null, null, null, null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-10B"),
                        ChildrenFixtures.childSafetyReport("SR-C-10B")),
                null);
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, correction))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("CORRECTION_NOT_IN_PUBLICITY"));
    }

    @Test
    void correctionVersionMismatchConflict409() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-11", "P-C-11"));
        String filingNo = submitted.filing().filingNo();

        CorrectChildrenFilingRequest staleVersion = new CorrectChildrenFilingRequest(
                "REQ-C-11C", 99, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, staleVersion))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void failedCorrectionKeepsOldRevisionAndDoesNotOccupyKey() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-12", "P-C-12"));
        String filingNo = submitted.filing().filingNo();

        // 补正材料缺儿童档报告 → 422，旧修订保持 PUBLIC，键不占用
        CorrectChildrenFilingRequest bad = new CorrectChildrenFilingRequest(
                "REQ-C-12C", 1, null, null, null, null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-12B")), null);
        assertThatThrownBy(() -> childrenFilingService.correct(filingNo, bad))
                .isInstanceOf(UnprocessableException.class);
        assertThat(childrenFilingPort.findVersion(filingNo, 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.PUBLIC);
        assertThat(childrenFilingPort.currentVersion("P-C-12")).isEqualTo(1);

        // 同一 requestId 补齐后成功
        CorrectChildrenFilingRequest good = new CorrectChildrenFilingRequest(
                "REQ-C-12C", 1, null, null, null, null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-12B"),
                        ChildrenFixtures.childSafetyReport("SR-C-12B")), null);
        ChildrenFilingResultResponse ok = childrenFilingService.correct(filingNo, good);
        assertThat(ok.filing().versionNo()).isEqualTo(2);
        assertThat(ok.filing().revisionNo()).isEqualTo(2);
    }

    @Test
    void idempotencyReplayAndParamMismatch409() {
        ChildrenFilingResultResponse first =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-13", "P-C-13"));
        ChildrenFilingResultResponse replay =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-13", "P-C-13"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.filing().filingNo()).isEqualTo(first.filing().filingNo());
        assertThat(childrenFilingPort.findByProduct("P-C-13")).hasSize(1);

        SubmitChildrenFilingRequest different = new SubmitChildrenFilingRequest(
                "REQ-C-13", "P-C-13", "改名儿童霜", 0, 144,
                ChildrenFixtures.gentleFormulation(), List.of("MOISTURIZING"),
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-13"),
                        ChildrenFixtures.childSafetyReport("SR-C-13")),
                List.of(ChildrenFixtures.infantLabel("0~36月"), ChildrenFixtures.childLabel("37~144月")),
                List.of());
        assertThatThrownBy(() -> childrenFilingService.submit(different))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void substantiveChangeOnAgeRangeCreatesNewFilingAndAtomicSwitchOnExpiry() {
        childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-14", "P-C-14"));
        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        // 实质变更：年龄范围收窄为 37~144（单档），资料按新档备齐
        ChangeChildrenFilingRequest change = new ChangeChildrenFilingRequest(
                "REQ-C-14X", 1, null, 37, 144, null, null,
                List.of(ChildrenFixtures.childSafetyReport("SR-C-14X")),
                List.of(ChildrenFixtures.childLabel("适用于37~144月儿童")), null);
        ChildrenFilingResultResponse changed = childrenFilingService.change("P-C-14", change);

        assertThat(changed.filing().filingNo()).isEqualTo("SYN-C-000002");
        assertThat(changed.filing().parentFilingNo()).isEqualTo("SYN-C-000001");
        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().versionNo()).isEqualTo(2);
        assertThat(changed.filing().revisionNo()).isEqualTo(1);
        assertThat(changed.filing().status()).isEqualTo(ChildrenFilingStatus.PUBLIC.name());
        // 新版本公示期间旧备案仍有效
        assertThat(childrenFilingPort.findVersion("SYN-C-000001", 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.EFFECTIVE);

        // 新版本公示期满：同事务新版本生效 + 旧版本 SUPERSEDED + 槽位切换（原子且唯一）
        clock.setInstant(changed.filing().publicExpiresAtUtc());
        childrenFilingService.expireDuePublicNotices(null);
        assertThat(childrenFilingPort.findVersion("SYN-C-000002", 2, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.EFFECTIVE);
        assertThat(childrenFilingPort.findVersion("SYN-C-000001", 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.SUPERSEDED);
        var slot = childrenFilingPort.findActiveSlot("P-C-14").orElseThrow();
        assertThat(slot.filingNo()).isEqualTo("SYN-C-000002");
        assertThat(slot.versionNo()).isEqualTo(2);
    }

    @Test
    void labelRangeChangeIsSubstantiveNotSimple() {
        childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-15", "P-C-15"));
        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        // 标识适用范围改变必须走实质变更，不能伪装为包装文字简易变更
        ChangeChildrenFilingRequest change = new ChangeChildrenFilingRequest(
                "REQ-C-15X", 1, null, null, null, null, null, null,
                List.of(ChildrenFixtures.infantLabel("适用于0~36月婴幼儿"),
                        new AgeLabelRequest("CHILD", "适用于30~144月", 30, 144)),
                "包装文字微调");
        ChildrenFilingResultResponse changed = childrenFilingService.change("P-C-15", change);

        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-C-000002");
        assertThat(changed.filing().status()).isEqualTo(ChildrenFilingStatus.PUBLIC.name());
    }

    @Test
    void packagingOnlyChangeIsSimpleAndImmediate() {
        childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-16", "P-C-16"));
        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        ChangeChildrenFilingRequest change = new ChangeChildrenFilingRequest(
                "REQ-C-16X", 1, null, null, null, null, null, null, null, "新包装：避光保存");
        ChildrenFilingResultResponse changed = childrenFilingService.change("P-C-16", change);

        assertThat(changed.filing().changeType()).isEqualTo("SIMPLE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-C-000001");
        assertThat(changed.filing().versionNo()).isEqualTo(2);
        assertThat(changed.filing().status()).isEqualTo(ChildrenFilingStatus.EFFECTIVE.name());
        assertThat(childrenFilingPort.findVersion("SYN-C-000001", 1, 1).orElseThrow().status())
                .isEqualTo(ChildrenFilingStatus.SUPERSEDED);
        assertThat(childrenFilingPort.findActiveSlot("P-C-16").orElseThrow().versionNo()).isEqualTo(2);
    }

    @Test
    void noChangeRejected422() {
        childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-17", "P-C-17"));
        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        ChangeChildrenFilingRequest noop = new ChangeChildrenFilingRequest(
                "REQ-C-17X", 1, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> childrenFilingService.change("P-C-17", noop))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("NO_CHANGE"));
    }

    @Test
    void changeOnMissingProduct404AndMissingFilingCorrection404() {
        ChangeChildrenFilingRequest change = new ChangeChildrenFilingRequest(
                "REQ-C-18X", 1, null, null, null, null, null, null, null, "包装");
        assertThatThrownBy(() -> childrenFilingService.change("P-NONE", change))
                .isInstanceOf(NotFoundException.class);

        CorrectChildrenFilingRequest correction = new CorrectChildrenFilingRequest(
                "REQ-C-18C", 1, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> childrenFilingService.correct("SYN-C-999999", correction))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void evidenceQueryReturnsFrozenReportsRuleVersionLabelDigestAndReviews() {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-19", "P-C-19"));
        String filingNo = submitted.filing().filingNo();
        childrenFilingService.correct(filingNo, new CorrectChildrenFilingRequest(
                "REQ-C-19C", 1, null, null, null, null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-19B"),
                        ChildrenFixtures.childSafetyReport("SR-C-19B")),
                List.of(ChildrenFixtures.infantLabel("新标识0~36月"),
                        ChildrenFixtures.childLabel("新标识37~144月"))));

        ChildrenEvidenceResponse evidence = childrenFilingService.getEvidence(filingNo);
        assertThat(evidence.filingNo()).isEqualTo(filingNo);
        assertThat(evidence.revisions()).hasSize(2);
        var latest = evidence.revisions().get(1);
        assertThat(latest.versionNo()).isEqualTo(2);
        assertThat(latest.revisionNo()).isEqualTo(2);
        assertThat(latest.ruleVersion()).isEqualTo(1);
        assertThat(latest.labelDigest()).isNotBlank();
        assertThat(latest.safetyReports()).extracting("reportNo")
                .containsExactlyInAnyOrder("SR-I-19B", "SR-C-19B");
        assertThat(latest.reviewDetails()).extracting("ruleCode")
                .contains("BAND_SAFETY_REPORT", "SAFETY_REPORT_COVERAGE", "CHILD_CONCENTRATION");
        // 旧修订证据保留（待审批准失效）
        assertThat(evidence.revisions().get(0).status())
                .isEqualTo(ChildrenFilingStatus.REVISION_SUPERSEDED.name());
    }

    @Test
    void versionIncrementsByOneOnEachSuccessfulModification() {
        ChildrenFilingResultResponse v1 =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-20", "P-C-20"));
        assertThat(v1.filing().versionNo()).isEqualTo(1);

        ChildrenFilingResultResponse v2 = childrenFilingService.correct(v1.filing().filingNo(),
                new CorrectChildrenFilingRequest("REQ-C-20C", 1, null, null, null, null, null,
                        List.of(ChildrenFixtures.infantSafetyReport("SR-I-20B"),
                                ChildrenFixtures.childSafetyReport("SR-C-20B")), null));
        assertThat(v2.filing().versionNo()).isEqualTo(2);

        clock.setInstant(EXPIRES_EXPECTED);
        childrenFilingService.expireDuePublicNotices(null);

        ChildrenFilingResultResponse v3 = childrenFilingService.change("P-C-20",
                new ChangeChildrenFilingRequest("REQ-C-20X", 2, null, null, null, null, null,
                        null, null, "新包装"));
        assertThat(v3.filing().versionNo()).isEqualTo(3);
    }

    @Test
    void submitWhileInFlightRejected422() {
        childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-C-21", "P-C-21"));

        assertThatThrownBy(() -> childrenFilingService.submit(
                ChildrenFixtures.crossBandSubmit("REQ-C-22", "P-C-21")))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("CHILD_PRODUCT_ALREADY_IN_FLIGHT"));
    }
}
