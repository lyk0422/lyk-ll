package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildBandDossierRequest;
import com.example.starter.cosmetic.web.dto.ChildEvidenceResponse;
import com.example.starter.cosmetic.web.dto.CorrectionFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 儿童备案分龄审查 H2 集成测试：单档/跨档提交、受理冻结证据、缺项一次列全零生效、
 * 材料补正生成新修订并使旧修订失效、实质变更判定、expectedVersion 与幂等边界。
 */
class ChildFilingLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private FilingPort filingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    // ============================ 提交与分龄审查 ============================

    @Test
    void childSubmissionSingleBandPassesAndFreezesEvidence() {
        FilingResultResponse result = filingService.submit(
                FilingFixtures.childSubmitBand036("REQ-C-1", "P-C-1"));

        assertThat(result.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(result.filing().filingCategory()).isEqualTo("CHILD");
        assertThat(result.filing().ageLowerMonths()).isEqualTo(0);
        assertThat(result.filing().ageUpperMonths()).isEqualTo(36);
        // 受理冻结：分龄规则版本、报告清单、标识文本摘要
        assertThat(result.filing().ageRuleVersion()).isEqualTo("AGE-RULE-V1");
        assertThat(result.filing().childDossiers()).hasSize(1);
        assertThat(result.filing().childDossiers().get(0).reportNo()).isEqualTo("CS-REQ-C-1");
        assertThat(result.filing().labelDigest()).isNotBlank();
        assertThat(result.filing().publicExpiresAtUtc()).isEqualTo(EXPIRES_EXPECTED);

        // 判定证据查询：冻结材料与审查明细可复算
        ChildEvidenceResponse evidence = filingService.getChildEvidence("SYN-F-000001", null);
        assertThat(evidence.ageRuleVersion()).isEqualTo("AGE-RULE-V1");
        assertThat(evidence.frozenDossiers()).hasSize(1);
        assertThat(evidence.labelDigest()).isEqualTo(result.filing().labelDigest());
        assertThat(evidence.reviewDetails()).extracting("ruleCode")
                .contains("CHILD_AGE_RANGE", "CHILD_CLAIM_CATALOG", "CHILD_BAND_MATERIAL",
                        "CHILD_REPORT_INDEPENDENT", "CHILD_RESTRICTED_CONCENTRATION");
    }

    @Test
    void crossBandSubmissionRequiresBothBandsAndListsAllMissingAtOnce() {
        // 跨档 0~144 只提供 0~36 档材料，且宣称含非儿童功效：缺项一次列全
        SubmitFilingRequest request = new SubmitFilingRequest("REQ-C-2", "P-C-2", "合成儿童霜",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING", "SUNSCREEN"), List.of(),
                "CHILD", 0, 144, List.of(FilingFixtures.band036Dossier("CS-C-2", 0, 36)));

        FilingResultResponse result = filingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        // 零生效：不进入公示
        assertThat(result.filing().publicNoticeDateUtc()).isNull();
        assertThat(result.filing().publicExpiresAtUtc()).isNull();
        assertThat(filingPort.findActiveSlot("P-C-2")).isEmpty();
        // 缺项一次列全：非儿童功效 + 缺 37~144 档材料
        assertThat(result.filing().rejectReason())
                .contains("CHILD_CLAIM_CATALOG").contains("SUNSCREEN")
                .contains("CHILD_BAND_MATERIAL").contains("BAND_37_144");
    }

    @Test
    void crossBandSubmissionPassesWithBothDossiers() {
        FilingResultResponse result = filingService.submit(
                FilingFixtures.childSubmitCrossBand("REQ-C-3", "P-C-3"));

        assertThat(result.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(result.filing().childDossiers()).hasSize(2);
    }

    @Test
    void adultEfficacyReportCannotSubstituteChildSafetyReport() {
        // 先登记成人功效评价试验报告（另一产品的成人防晒备案）
        filingService.submit(FilingFixtures.sunscreenSubmit(
                "REQ-C-4A", "P-C-4A", LocalDate.of(2025, 1, 1), true));
        String adultReportNo = "R-REQ-C-4A";

        // 儿童备案以成人报告编号充当儿童安全报告 → 拒绝
        SubmitFilingRequest request = new SubmitFilingRequest("REQ-C-4B", "P-C-4B", "合成婴儿霜",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 36,
                List.of(new ChildBandDossierRequest("BAND_0_36", adultReportNo, 0, 36, true,
                        "成人报告结论", "适用于0-36个月婴幼儿")));

        FilingResultResponse result = filingService.submit(request);

        assertThat(result.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(result.filing().rejectReason())
                .contains("CHILD_REPORT_INDEPENDENT")
                .contains("成人功效报告不能替代儿童安全报告");
    }

    @Test
    void crossBandConcentrationJudgedByStricterLimit() {
        List<FormulationItemRequest> formulation = List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("78.0000")),
                new FormulationItemRequest("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.6000")));
        SubmitFilingRequest over = new SubmitFilingRequest("REQ-C-5", "P-C-5", "合成儿童乳",
                formulation, List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 144,
                List.of(FilingFixtures.band036Dossier("CS-C-5A", 0, 36),
                        FilingFixtures.band37144Dossier("CS-C-5B", 37, 144)));

        // 0.6% 超过较严限值 0.5%（0~36 月档）→ 拒绝
        FilingResultResponse rejected = filingService.submit(over);
        assertThat(rejected.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(rejected.filing().rejectReason()).contains("CHILD_RESTRICTED_CONCENTRATION");

        // 恰等较严限值 0.5% → 合格
        List<FormulationItemRequest> atLimit = List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("78.0000")),
                new FormulationItemRequest("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5000")));
        FilingResultResponse passed = filingService.submit(new SubmitFilingRequest(
                "REQ-C-5B", "P-C-5B", "合成儿童乳", atLimit, List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 144,
                List.of(FilingFixtures.band036Dossier("CS-C-5C", 0, 36),
                        FilingFixtures.band37144Dossier("CS-C-5D", 37, 144))));
        assertThat(passed.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
    }

    @Test
    void invalidAgeRangeIsRejectedAsBadArgument() {
        SubmitFilingRequest inverted = new SubmitFilingRequest("REQ-C-6", "P-C-6", "合成婴儿霜",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 40, 36, List.of(FilingFixtures.band036Dossier("CS-C-6", 0, 36)));

        assertThatThrownBy(() -> filingService.submit(inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0≤下限≤上限");
        // 参数非法：不产生任何备案记录
        assertThat(filingPort.findByProduct("P-C-6")).isEmpty();
    }

    @Test
    void adultSubmissionWithChildFieldsIsRejectedAsBadArgument() {
        SubmitFilingRequest request = new SubmitFilingRequest("REQ-C-7", "P-C-7", "合成保湿乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                null, 0, 36, null);

        assertThatThrownBy(() -> filingService.submit(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成人备案不得携带儿童字段");
    }

    // ============================ 材料补正 ============================

    @Test
    void correctionCreatesNewRevisionAndInvalidatesOldAtomically() {
        // 首次提交缺 37~144 档材料 → 待补正
        FilingResultResponse rejected = filingService.submit(new SubmitFilingRequest(
                "REQ-C-8", "P-C-8", "合成儿童乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 144, List.of(FilingFixtures.band036Dossier("CS-C-8A", 0, 36))));
        assertThat(rejected.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        String filingNo = rejected.filing().filingNo();

        // 补正：补齐两档材料，expectedVersion=1
        CorrectionFilingRequest correction = new CorrectionFilingRequest("REQ-C-8B", 1,
                null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-8A", 0, 36),
                        FilingFixtures.band37144Dossier("CS-C-8B", 37, 144)));
        FilingResultResponse corrected = filingService.correct(filingNo, correction);

        // 新修订版本号 +1，进入公示；公示只能使用最新修订
        assertThat(corrected.filing().versionNo()).isEqualTo(2);
        assertThat(corrected.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(corrected.filing().publicExpiresAtUtc()).isEqualTo(EXPIRES_EXPECTED);

        // 同事务：旧修订待审批准失效（SUPERSEDED）
        assertThat(filingPort.findVersion(filingNo, 1).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);
        assertThat(filingPort.findVersion(filingNo, 2).orElseThrow().status())
                .isEqualTo(FilingStatus.PUBLIC);

        // 期满生效只可能是最新修订
        clock.setInstant(EXPIRES_EXPECTED);
        assertThat(filingService.expireDuePublicNotices(null)).isEqualTo(1);
        assertThat(filingPort.findVersion(filingNo, 2).orElseThrow().status())
                .isEqualTo(FilingStatus.EFFECTIVE);
        assertThat(filingPort.findActiveSlot("P-C-8").orElseThrow().versionNo()).isEqualTo(2);
    }

    @Test
    void correctionFailingReviewKeepsPendingAndAllowsFurtherCorrection() {
        // 首次提交缺材料 → v1 待补正
        FilingResultResponse rejected = filingService.submit(new SubmitFilingRequest(
                "REQ-C-9", "P-C-9", "合成儿童乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 144, List.of()));
        String filingNo = rejected.filing().filingNo();

        // 第一次补正仍缺 37~144 档 → v2 待补正，v1 失效
        FilingResultResponse second = filingService.correct(filingNo, new CorrectionFilingRequest(
                "REQ-C-9B", 1, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-9A", 0, 36))));
        assertThat(second.filing().versionNo()).isEqualTo(2);
        assertThat(second.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
        assertThat(filingPort.findVersion(filingNo, 1).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);

        // 第二次补正补齐 → v3 公示，v2 失效
        FilingResultResponse third = filingService.correct(filingNo, new CorrectionFilingRequest(
                "REQ-C-9C", 2, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-9A", 0, 36),
                        FilingFixtures.band37144Dossier("CS-C-9B", 37, 144))));
        assertThat(third.filing().versionNo()).isEqualTo(3);
        assertThat(third.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(filingPort.findVersion(filingNo, 2).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);
        assertThat(filingPort.findAllVersions(filingNo)).hasSize(3);
    }

    @Test
    void correctionVersionMismatchAndStateErrors() {
        FilingResultResponse rejected = filingService.submit(new SubmitFilingRequest(
                "REQ-C-10", "P-C-10", "合成儿童乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 36, List.of()));
        String filingNo = rejected.filing().filingNo();

        // expectedVersion 不一致 → 409
        assertThatThrownBy(() -> filingService.correct(filingNo, new CorrectionFilingRequest(
                "REQ-C-10B", 5, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-10", 0, 36)))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));

        // 备案不存在 → 404
        assertThatThrownBy(() -> filingService.correct("SYN-F-999999", new CorrectionFilingRequest(
                "REQ-C-10C", 1, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-10", 0, 36)))))
                .isInstanceOf(NotFoundException.class);

        // 成人备案 → 422
        FilingResultResponse adult = filingService.submit(FilingFixtures.ordinarySubmit("REQ-C-10D", "P-C-10D"));
        assertThatThrownBy(() -> filingService.correct(adult.filing().filingNo(),
                new CorrectionFilingRequest("REQ-C-10E", 1, null, null, null, null, null,
                        null, null, List.of(FilingFixtures.band036Dossier("CS-C-10", 0, 36)))))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_CHILD_FILING"));
    }

    @Test
    void correctionOnPublicRevisionRejectedAsUnprocessable() {
        FilingResultResponse passed = filingService.submit(
                FilingFixtures.childSubmitBand036("REQ-C-11", "P-C-11"));
        assertThat(passed.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());

        assertThatThrownBy(() -> filingService.correct(passed.filing().filingNo(),
                new CorrectionFilingRequest("REQ-C-11B", 1, null, null, null, null, null,
                        null, null, List.of(FilingFixtures.band036Dossier("CS-C-11", 0, 36)))))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("CORRECTION_NOT_ALLOWED"));
    }

    @Test
    void failedCorrectionDoesNotConsumeIdempotencyKey() {
        FilingResultResponse rejected = filingService.submit(new SubmitFilingRequest(
                "REQ-C-12", "P-C-12", "合成儿童乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 36, List.of()));
        String filingNo = rejected.filing().filingNo();

        // 失败补正（版本不一致 409）不占键
        assertThatThrownBy(() -> filingService.correct(filingNo, new CorrectionFilingRequest(
                "REQ-C-12B", 9, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-12", 0, 36)))))
                .isInstanceOf(ConflictException.class);

        // 同一 requestId 以正确参数重试：不因前次失败而 409
        FilingResultResponse corrected = filingService.correct(filingNo, new CorrectionFilingRequest(
                "REQ-C-12B", 1, null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-12", 0, 36))));
        assertThat(corrected.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(corrected.filing().versionNo()).isEqualTo(2);
    }

    // ============================ 幂等与版本 ============================

    @Test
    void childSubmitAndCorrectionAreIdempotent() {
        SubmitFilingRequest submit = new SubmitFilingRequest("REQ-C-13", "P-C-13", "合成儿童乳",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 36, List.of());
        FilingResultResponse first = filingService.submit(submit);
        FilingResultResponse replay = filingService.submit(submit);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.filing().filingNo()).isEqualTo(first.filing().filingNo());
        assertThat(filingPort.findByProduct("P-C-13")).hasSize(1);

        String filingNo = first.filing().filingNo();
        CorrectionFilingRequest correction = new CorrectionFilingRequest("REQ-C-13B", 1,
                null, null, null, null, null, null, null,
                List.of(FilingFixtures.band036Dossier("CS-C-13", 0, 36)));
        FilingResultResponse correctedFirst = filingService.correct(filingNo, correction);
        FilingResultResponse correctedReplay = filingService.correct(filingNo, correction);
        assertThat(correctedReplay.idempotentReplay()).isTrue();
        assertThat(correctedReplay.atUtc()).isEqualTo(correctedFirst.atUtc());
        // 重放不产生额外修订
        assertThat(filingPort.findAllVersions(filingNo)).hasSize(2);
    }

    @Test
    void childSubmitSameKeyDifferentParamsConflicts() {
        filingService.submit(FilingFixtures.childSubmitBand036("REQ-C-14", "P-C-14"));

        SubmitFilingRequest changed = new SubmitFilingRequest("REQ-C-14", "P-C-14", "合成婴儿霜",
                FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 24, List.of(FilingFixtures.band036Dossier("CS-C-14", 0, 36)));
        assertThatThrownBy(() -> filingService.submit(changed))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    // ============================ 变更：儿童属性必须实质变更 ============================

    @Test
    void childAgeOrLabelChangeMustBeSubstantiveNotSimple() {
        // 儿童备案生效
        filingService.submit(FilingFixtures.childSubmitBand036("REQ-C-15", "P-C-15"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 年龄上限 36 → 30 且同时改包装文字：必须实质变更（新备案编号），不得简易变更
        ChangeFilingRequest ageChange = new ChangeFilingRequest("REQ-C-15B", null, null, null,
                null, "新包装文字", 1, null, null, 30,
                List.of(new ChildBandDossierRequest("BAND_0_36", "CS-C-15", 0, 30, true,
                        "结论支持0-30月使用安全", "适用于0-30个月婴幼儿")));
        FilingResultResponse changed = filingService.change("P-C-15", ageChange);

        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-F-000002");
        assertThat(changed.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(changed.filing().ageUpperMonths()).isEqualTo(30);
        // 旧备案在新版本生效前继续有效
        assertThat(filingPort.findVersion("SYN-F-000001", 1).orElseThrow().status())
                .isEqualTo(FilingStatus.EFFECTIVE);
    }

    @Test
    void childLabelScopeChangeAloneIsSubstantive() {
        filingService.submit(FilingFixtures.childSubmitBand036("REQ-C-16", "P-C-16"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 仅标识文本变化（标识适用范围改变）→ 实质变更
        ChangeFilingRequest labelChange = new ChangeFilingRequest("REQ-C-16B", null, null, null,
                null, null, 1, null, null, null,
                List.of(new ChildBandDossierRequest("BAND_0_36", "CS-REQ-C-16", 0, 36, true,
                        "结论支持0-36月婴幼儿使用安全", "适用于0-36个月婴幼儿（新版标识）")));
        FilingResultResponse changed = filingService.change("P-C-16", labelChange);

        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-F-000002");
        assertThat(changed.filing().labelDigest())
                .isNotEqualTo(filingPort.findVersion("SYN-F-000001", 1).orElseThrow().labelDigest());
    }

    @Test
    void childPackagingOnlyChangeRemainsSimple() {
        filingService.submit(FilingFixtures.childSubmitBand036("REQ-C-17", "P-C-17"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 纯包装文字变更：简易变更，引用原编号，儿童冻结证据沿用
        FilingResultResponse changed = filingService.change("P-C-17",
                new ChangeFilingRequest("REQ-C-17B", null, null, null, null, "仅包装文字调整",
                        1, null, null, null, null));

        assertThat(changed.filing().changeType()).isEqualTo("SIMPLE");
        assertThat(changed.filing().filingNo()).isEqualTo("SYN-F-000001");
        assertThat(changed.filing().versionNo()).isEqualTo(2);
        assertThat(changed.filing().filingCategory()).isEqualTo("CHILD");
        assertThat(changed.filing().ageRuleVersion()).isEqualTo("AGE-RULE-V1");
    }

    @Test
    void childChangeRequiresExpectedVersion() {
        filingService.submit(FilingFixtures.childSubmitBand036("REQ-C-18", "P-C-18"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 缺 expectedVersion → 400
        assertThatThrownBy(() -> filingService.change("P-C-18",
                new ChangeFilingRequest("REQ-C-18B", null, null, null, null, "包装",
                        null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");

        // expectedVersion 不一致 → 409
        assertThatThrownBy(() -> filingService.change("P-C-18",
                new ChangeFilingRequest("REQ-C-18C", null, null, null, null, "包装",
                        7, null, null, null, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void adultToChildCategoryChangeIsSubstantive() {
        // 成人备案生效
        filingService.submit(FilingFixtures.ordinarySubmit("REQ-C-19", "P-C-19"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        // 转为儿童备案：必须实质变更并提供完整儿童材料
        ChangeFilingRequest toChild = new ChangeFilingRequest("REQ-C-19B", null, null, null,
                null, null, 1, "CHILD", 0, 36,
                List.of(FilingFixtures.band036Dossier("CS-C-19", 0, 36)));
        FilingResultResponse changed = filingService.change("P-C-19", toChild);

        assertThat(changed.filing().changeType()).isEqualTo("SUBSTANTIVE");
        assertThat(changed.filing().filingCategory()).isEqualTo("CHILD");
        assertThat(changed.filing().status()).isEqualTo(FilingStatus.PUBLIC.name());
        assertThat(changed.filing().ageRuleVersion()).isEqualTo("AGE-RULE-V1");
    }

    // ============================ 证据与规则查询 ============================

    @Test
    void ageRuleQueryReturnsSeededBands() {
        AgeRuleResponse rule = filingService.getAgeRule();

        assertThat(rule.ruleVersion()).isEqualTo("AGE-RULE-V1");
        assertThat(rule.bands()).hasSize(2);
        assertThat(rule.bands().get(0).bandCode()).isEqualTo("BAND_0_36");
        assertThat(rule.bands().get(0).lowerMonths()).isEqualTo(0);
        assertThat(rule.bands().get(0).upperMonths()).isEqualTo(36);
        assertThat(rule.bands().get(1).bandCode()).isEqualTo("BAND_37_144");
        assertThat(rule.bands().get(1).upperMonths()).isEqualTo(144);
    }

    @Test
    void childEvidenceForAdultFilingIsUnprocessable() {
        FilingResultResponse adult = filingService.submit(
                FilingFixtures.ordinarySubmit("REQ-C-20", "P-C-20"));

        assertThatThrownBy(() -> filingService.getChildEvidence(adult.filing().filingNo(), null))
                .isInstanceOfSatisfying(UnprocessableException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_CHILD_FILING"));
        assertThatThrownBy(() -> filingService.getChildEvidence("SYN-F-999999", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void childFilingFullLifecycleEffectiveAndEvidenceFrozen() {
        FilingResultResponse submitted = filingService.submit(
                FilingFixtures.childSubmitCrossBand("REQ-C-21", "P-C-21"));
        String filingNo = submitted.filing().filingNo();

        clock.setInstant(EXPIRES_EXPECTED);
        assertThat(filingService.expireDuePublicNotices(null)).isEqualTo(1);

        FilingRecord effective = filingPort.findVersion(filingNo, 1).orElseThrow();
        assertThat(effective.status()).isEqualTo(FilingStatus.EFFECTIVE);
        assertThat(filingPort.findActiveSlot("P-C-21")).isPresent();

        // 生效后证据仍冻结可查
        ChildEvidenceResponse evidence = filingService.getChildEvidence(filingNo, 1);
        assertThat(evidence.status()).isEqualTo(FilingStatus.EFFECTIVE.name());
        assertThat(evidence.frozenDossiers()).hasSize(2);
        assertThat(evidence.ageRuleVersion()).isEqualTo("AGE-RULE-V1");
        assertThat(evidence.labelDigest()).isEqualTo(submitted.filing().labelDigest());
    }
}
