package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.testsupport.LabelPrintFixtures;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.IssueLabelsRequest;
import com.example.starter.cosmetic.web.dto.LabelDestructionResultResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResultResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包装标签印次台账 H2 集成测试：登记（成人/儿童分档摘要）、领用余额与包装批、
 * 销毁留证、备案换版/注销原子禁用与风险关联、expectedVersion 与幂等边界。
 */
class LabelPrintLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private LabelPrintService labelPrintService;

    // ============================ 印次登记 ============================

    @Test
    void adultPrintRunRegistersAgainstEffectiveFilingWithConservation() {
        String filingNo = LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR1-S", "P-PR-1");

        PrintRunResultResponse result = labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR1-R", "RUN-PR-1", "P-PR-1", LabelPrintFixtures.adultDigest(null), null, 100));

        PrintRunResponse run = result.printRun();
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(run.printRunNo()).isEqualTo("RUN-PR-1");
        assertThat(run.filingNo()).isEqualTo(filingNo);
        assertThat(run.filingVersionNo()).isEqualTo(1);
        assertThat(run.filingCategory()).isEqualTo("ADULT");
        assertThat(run.bandDigests()).isNull();
        assertThat(run.status()).isEqualTo("ACTIVE");
        assertThat(run.versionNo()).isEqualTo(1);
        // 张数守恒：印刷总数 = 已领 + 已毁 + 剩余；禁用剩余为 0
        assertThat(run.printedCount()).isEqualTo(100);
        assertThat(run.printedCount())
                .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
        assertThat(run.remainingCount()).isEqualTo(100);
        assertThat(run.disabledRemainingCount()).isZero();
    }

    @Test
    void registerRejectsWhenFilingNotEffective() {
        // 产品无任何备案
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR2-A", "RUN-PR-2A", "P-PR-2NONE", LabelPrintFixtures.adultDigest(null), null, 10)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("无已生效备案");

        // 备案仍在公示中（未生效）
        filingService.submit(FilingFixtures.ordinarySubmit("REQ-PR2-S", "P-PR-2PUB"));
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR2-B", "RUN-PR-2B", "P-PR-2PUB", LabelPrintFixtures.adultDigest(null), null, 10)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("无已生效备案");
    }

    @Test
    void registerRejectsLabelDigestMismatch() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR3-S", "P-PR-3");

        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR3-R", "RUN-PR-3", "P-PR-3", "0".repeat(64), null, 10)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("标签文本摘要");
    }

    @Test
    void duplicatePrintRunNoRejected() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR4-S", "P-PR-4");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR4-R", "RUN-PR-4", "P-PR-4", LabelPrintFixtures.adultDigest(null), null, 10));

        // 同印次号（不同 requestId）唯一冲突：409
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR4-R2", "RUN-PR-4", "P-PR-4", LabelPrintFixtures.adultDigest(null), null, 20)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("印次号已存在");
    }

    @Test
    void registerIdempotentReplayAndParamMismatch() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR5-S", "P-PR-5");
        RegisterPrintRunRequest request = new RegisterPrintRunRequest(
                "REQ-PR5-R", "RUN-PR-5", "P-PR-5", LabelPrintFixtures.adultDigest(null), null, 50);

        PrintRunResultResponse first = labelPrintService.register(request);
        PrintRunResultResponse replay = labelPrintService.register(request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.printRun().printRunNo()).isEqualTo(first.printRun().printRunNo());
        // 同键异参：409
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR5-R", "RUN-PR-5B", "P-PR-5", LabelPrintFixtures.adultDigest(null), null, 50)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
        // 失败不占键：摘要错误拒绝后，同键修正参数可成功
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR5-F", "RUN-PR-5F", "P-PR-5", "1".repeat(64), null, 50)))
                .isInstanceOf(UnprocessableException.class);
        PrintRunResultResponse retried = labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR5-F", "RUN-PR-5F", "P-PR-5", LabelPrintFixtures.adultDigest(null), null, 50));
        assertThat(retried.printRun().status()).isEqualTo("ACTIVE");
    }

    // ============================ 儿童印次分档摘要 ============================

    @Test
    void childPrintRunRequiresPerBandDigestsNotGlobalOnly() {
        String filingNo = LabelPrintFixtures.makeChildCrossBandEffective(
                filingService, clock, "REQ-PR6-S", "P-PR-6");
        String globalDigest = filingService.getFiling(filingNo).labelDigest();

        // 只拿一个全局标识代替（无分档摘要）：拒绝，缺档一次列全
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR6-R", "RUN-PR-6", "P-PR-6", globalDigest, null, 100)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> {
                    assertThat(e.code()).isEqualTo("CHILD_BAND_DIGEST_VIOLATION");
                    assertThat(e.violations()).anyMatch(v -> v.contains("BAND_0_36") && v.contains("缺档"));
                    assertThat(e.violations()).anyMatch(v -> v.contains("BAND_37_144") && v.contains("缺档"));
                });
    }

    @Test
    void childPrintRunRejectsMissingBandAndDigestMismatch() {
        String filingNo = LabelPrintFixtures.makeChildCrossBandEffective(
                filingService, clock, "REQ-PR7-S", "P-PR-7");
        String globalDigest = filingService.getFiling(filingNo).labelDigest();
        Map<String, String> digests = LabelPrintFixtures.crossBandDigests();

        // 缺 37~144 档
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR7-A", "RUN-PR-7A", "P-PR-7", globalDigest,
                Map.of("BAND_0_36", digests.get("BAND_0_36")), 100)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.violations()).anyMatch(v -> v.contains("BAND_37_144") && v.contains("缺档")));

        // 0~36 档摘要错
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR7-B", "RUN-PR-7B", "P-PR-7", globalDigest,
                Map.of("BAND_0_36", "f".repeat(64), "BAND_37_144", digests.get("BAND_37_144")), 100)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.violations()).anyMatch(v -> v.contains("BAND_0_36") && v.contains("摘要错")));

        // 夹带备案外的未知档
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR7-C", "RUN-PR-7C", "P-PR-7", globalDigest,
                Map.of("BAND_0_36", digests.get("BAND_0_36"),
                        "BAND_37_144", digests.get("BAND_37_144"),
                        "BAND_X", "e".repeat(64)), 100)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.violations()).anyMatch(v -> v.contains("未知档")));

        // 全局摘要错（分档正确也不行）
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR7-D", "RUN-PR-7D", "P-PR-7", "0".repeat(64), digests, 100)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("LABEL_DIGEST_MISMATCH"));
    }

    @Test
    void childPrintRunRegistersWithPerBandDigestsFrozen() {
        String filingNo = LabelPrintFixtures.makeChildCrossBandEffective(
                filingService, clock, "REQ-PR8-S", "P-PR-8");
        String globalDigest = filingService.getFiling(filingNo).labelDigest();

        PrintRunResultResponse result = labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR8-R", "RUN-PR-8", "P-PR-8", globalDigest,
                LabelPrintFixtures.crossBandDigests(), 200));

        assertThat(result.printRun().filingCategory()).isEqualTo("CHILD");
        assertThat(result.printRun().labelDigest()).isEqualTo(globalDigest);
        // 各年龄档独立标识摘要逐档冻结
        assertThat(result.printRun().bandDigests())
                .containsEntry("BAND_0_36", LabelPrintFixtures.crossBandDigests().get("BAND_0_36"))
                .containsEntry("BAND_37_144", LabelPrintFixtures.crossBandDigests().get("BAND_37_144"));
        assertThat(result.printRun().remainingCount()).isEqualTo(200);
    }

    // ============================ 领用 ============================

    @Test
    void issueConservesCountsAndAllowsExactExhaustion() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR9-S", "P-PR-9");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR9-R", "RUN-PR-9", "P-PR-9", LabelPrintFixtures.adultDigest(null), null, 100));

        // 领 60：余额 40，版本 2
        LabelIssueResultResponse first = labelPrintService.issue("RUN-PR-9",
                new IssueLabelsRequest("REQ-PR9-I1", "BATCH-PR-9", 60, 1));
        assertThat(first.printRun().issuedCount()).isEqualTo(60);
        assertThat(first.printRun().remainingCount()).isEqualTo(40);
        assertThat(first.printRun().versionNo()).isEqualTo(2);
        assertThat(first.issue().issueId()).isNotNull();
        assertThat(first.issue().riskFlag()).isFalse();

        // 恰领完剩余 40：允许，余额 0
        LabelIssueResultResponse second = labelPrintService.issue("RUN-PR-9",
                new IssueLabelsRequest("REQ-PR9-I2", "BATCH-PR-9", 40, 2));
        assertThat(second.printRun().remainingCount()).isZero();

        // 超余额领用：422
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-9",
                new IssueLabelsRequest("REQ-PR9-I3", "BATCH-PR-9", 1, 3)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("未领余额不足");

        // 每包装批可多次领用且凭据可追溯
        assertThat(labelPrintService.listBatchIssues("BATCH-PR-9")).hasSize(2);
        assertThat(labelPrintService.listIssues("RUN-PR-9"))
                .extracting("quantity").containsExactly(60, 40);
        // 守恒
        PrintRunResponse run = labelPrintService.getPrintRun("RUN-PR-9");
        assertThat(run.printedCount())
                .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
    }

    @Test
    void issueIdempotencyAndFailureNotOccupyingKey() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR10-S", "P-PR-10");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR10-R", "RUN-PR-10", "P-PR-10", LabelPrintFixtures.adultDigest(null), null, 100));

        IssueLabelsRequest request = new IssueLabelsRequest("REQ-PR10-I1", "BATCH-PR-10", 60, 1);
        LabelIssueResultResponse first = labelPrintService.issue("RUN-PR-10", request);
        LabelIssueResultResponse replay = labelPrintService.issue("RUN-PR-10", request);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.issue().issueId()).isEqualTo(first.issue().issueId());
        // 重放不重复扣减
        assertThat(labelPrintService.getPrintRun("RUN-PR-10").issuedCount()).isEqualTo(60);

        // 同键异参：409
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-10",
                new IssueLabelsRequest("REQ-PR10-I1", "BATCH-PR-10", 61, 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");

        // 失败不占键：余额不足 422 后，同键修正参数成功
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-10",
                new IssueLabelsRequest("REQ-PR10-I2", "BATCH-PR-10", 999, 2)))
                .isInstanceOf(UnprocessableException.class);
        LabelIssueResultResponse retried = labelPrintService.issue("RUN-PR-10",
                new IssueLabelsRequest("REQ-PR10-I2", "BATCH-PR-10", 40, 2));
        assertThat(retried.printRun().remainingCount()).isZero();
    }

    @Test
    void issueRejectsStaleExpectedVersion() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR11-S", "P-PR-11");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR11-R", "RUN-PR-11", "P-PR-11", LabelPrintFixtures.adultDigest(null), null, 100));

        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-11",
                new IssueLabelsRequest("REQ-PR11-I1", "BATCH-PR-11", 10, 7)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void crossProductBatchBorrowingRejected() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR12-SA", "P-PR-12A");
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR12-SB", "P-PR-12B");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR12-RA", "RUN-PR-12A", "P-PR-12A", LabelPrintFixtures.adultDigest(null), null, 100));
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR12-RB", "RUN-PR-12B", "P-PR-12B", LabelPrintFixtures.adultDigest(null), null, 100));

        // 首次领用：包装批登记所属产品 P-PR-12A
        labelPrintService.issue("RUN-PR-12A", new IssueLabelsRequest("REQ-PR12-I1", "BATCH-SHARED", 10, 1));

        // 跨产品借用同一包装批：422
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-12B",
                new IssueLabelsRequest("REQ-PR12-I2", "BATCH-SHARED", 10, 1)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("跨产品借用拒绝");

        // 同产品另一印次可复用同一包装批
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR12-RA2", "RUN-PR-12A2", "P-PR-12A", LabelPrintFixtures.adultDigest(null), null, 50));
        LabelIssueResultResponse reused = labelPrintService.issue("RUN-PR-12A2",
                new IssueLabelsRequest("REQ-PR12-I3", "BATCH-SHARED", 5, 1));
        assertThat(reused.printRun().issuedCount()).isEqualTo(5);
        assertThat(labelPrintService.listBatchIssues("BATCH-SHARED")).hasSize(2);
    }

    // ============================ 销毁 ============================

    @Test
    void destructionReducesRemainingKeepsEvidenceAndNeverReturnsIssued() {
        LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-PR13-S", "P-PR-13");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR13-R", "RUN-PR-13", "P-PR-13", LabelPrintFixtures.adultDigest(null), null, 100));

        // 未领销毁 30：余量 70，版本 2，留证
        LabelDestructionResultResponse destroyed = labelPrintService.destroy("RUN-PR-13",
                new DestroyLabelsRequest("REQ-PR13-D1", 30, 1, "合成销毁：印次残次"));
        assertThat(destroyed.printRun().destroyedCount()).isEqualTo(30);
        assertThat(destroyed.printRun().remainingCount()).isEqualTo(70);
        assertThat(destroyed.destruction().destructionId()).isNotNull();
        assertThat(labelPrintService.listDestructions("RUN-PR-13")).hasSize(1);

        // 领 60：余量 10，版本 3
        labelPrintService.issue("RUN-PR-13", new IssueLabelsRequest("REQ-PR13-I1", "BATCH-PR-13", 60, 2));

        // 已领部分不能销毁退回余额：申请销毁 20 > 未领 10，422
        assertThatThrownBy(() -> labelPrintService.destroy("RUN-PR-13",
                new DestroyLabelsRequest("REQ-PR13-D2", 20, 3, "合成销毁：超未领")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已领部分不能销毁退回余额");

        // 恰销毁剩余 10：允许
        LabelDestructionResultResponse last = labelPrintService.destroy("RUN-PR-13",
                new DestroyLabelsRequest("REQ-PR13-D3", 10, 3, "合成销毁：清零"));
        assertThat(last.printRun().remainingCount()).isZero();
        // 守恒：100 = 60 已领 + 40 已毁 + 0 剩余
        PrintRunResponse run = labelPrintService.getPrintRun("RUN-PR-13");
        assertThat(run.printedCount())
                .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
        assertThat(run.issuedCount()).isEqualTo(60);
        assertThat(run.destroyedCount()).isEqualTo(40);
    }

    // ============================ 备案换版/注销联动 ============================

    @Test
    void substantiveChangeDisablesOldPrintRunAndFlagsRiskOnIssued() {
        String oldFilingNo = LabelPrintFixtures.makeAdultEffective(
                filingService, clock, "REQ-PR14-S", "P-PR-14");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR14-R", "RUN-PR-14", "P-PR-14", LabelPrintFixtures.adultDigest(null), null, 100));
        labelPrintService.issue("RUN-PR-14", new IssueLabelsRequest("REQ-PR14-I1", "BATCH-PR-14", 60, 1));

        // 实质变更新版本生效：原子禁用旧印次剩余标签
        LabelPrintFixtures.substantiveChangeAndEffectuate(
                filingService, clock, "REQ-PR14-C", "P-PR-14", null);

        PrintRunResponse run = labelPrintService.getPrintRun("RUN-PR-14");
        assertThat(run.status()).isEqualTo("DISABLED");
        assertThat(run.filingNo()).isEqualTo(oldFilingNo);
        assertThat(run.disabledReason()).contains("实质变更");
        // 区分禁用剩余：40 张未领剩余全部转为禁用剩余
        assertThat(run.remainingCount()).isEqualTo(40);
        assertThat(run.disabledRemainingCount()).isEqualTo(40);
        assertThat(run.versionNo()).isEqualTo(3);

        // 已领凭据仅追加风险关联，不抹历史
        assertThat(labelPrintService.listIssues("RUN-PR-14")).hasSize(1);
        assertThat(labelPrintService.listIssues("RUN-PR-14").get(0).riskFlag()).isTrue();
        assertThat(labelPrintService.listIssues("RUN-PR-14").get(0).riskNote()).contains("替代");

        // 换版后拒领旧剩余：当前版本 422（禁用终态，不允许复活）
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-14",
                new IssueLabelsRequest("REQ-PR14-I2", "BATCH-PR-14", 40, 3)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已禁用");
        // 旧版本号 409
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-14",
                new IssueLabelsRequest("REQ-PR14-I3", "BATCH-PR-14", 40, 2)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));

        // 禁用印次的未领剩余仍可登记销毁（不是复活）
        LabelDestructionResultResponse destroyed = labelPrintService.destroy("RUN-PR-14",
                new DestroyLabelsRequest("REQ-PR14-D1", 40, 3, "合成销毁：禁用剩余"));
        assertThat(destroyed.printRun().remainingCount()).isZero();
        assertThat(destroyed.printRun().status()).isEqualTo("DISABLED");
        // 守恒：100 = 60 已领 + 40 已毁 + 0 剩余
        PrintRunResponse finalRun = labelPrintService.getPrintRun("RUN-PR-14");
        assertThat(finalRun.printedCount())
                .isEqualTo(finalRun.issuedCount() + finalRun.destroyedCount() + finalRun.remainingCount());
    }

    @Test
    void withdrawDisablesPrintRunAndFlagsRisk() {
        String filingNo = LabelPrintFixtures.makeAdultEffective(
                filingService, clock, "REQ-PR15-S", "P-PR-15");
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR15-R", "RUN-PR-15", "P-PR-15", LabelPrintFixtures.adultDigest(null), null, 100));
        labelPrintService.issue("RUN-PR-15", new IssueLabelsRequest("REQ-PR15-I1", "BATCH-PR-15", 60, 1));

        filingService.withdraw(filingNo, "REQ-PR15-W", "合成注销");

        PrintRunResponse run = labelPrintService.getPrintRun("RUN-PR-15");
        assertThat(run.status()).isEqualTo("DISABLED");
        assertThat(run.disabledReason()).contains("注销");
        assertThat(run.disabledRemainingCount()).isEqualTo(40);
        assertThat(labelPrintService.listIssues("RUN-PR-15").get(0).riskFlag()).isTrue();
        assertThat(labelPrintService.listIssues("RUN-PR-15").get(0).riskNote()).contains("注销");
        // 禁用后拒领
        assertThatThrownBy(() -> labelPrintService.issue("RUN-PR-15",
                new IssueLabelsRequest("REQ-PR15-I2", "BATCH-PR-15", 10, 3)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已禁用");
    }

    @Test
    void childSubstantiveChangeDisablesChildPrintRun() {
        String filingNo = LabelPrintFixtures.makeChildCrossBandEffective(
                filingService, clock, "REQ-PR16-S", "P-PR-16");
        String globalDigest = filingService.getFiling(filingNo).labelDigest();
        labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR16-R", "RUN-PR-16", "P-PR-16", globalDigest,
                LabelPrintFixtures.crossBandDigests(), 100));
        labelPrintService.issue("RUN-PR-16", new IssueLabelsRequest("REQ-PR16-I1", "BATCH-PR-16", 30, 1));

        // 儿童备案实质变更（宣称变化，expectedVersion=1）生效后禁用儿童印次
        LabelPrintFixtures.substantiveChangeAndEffectuate(
                filingService, clock, "REQ-PR16-C", "P-PR-16", 1);

        PrintRunResponse run = labelPrintService.getPrintRun("RUN-PR-16");
        assertThat(run.status()).isEqualTo("DISABLED");
        assertThat(run.disabledRemainingCount()).isEqualTo(70);
        assertThat(labelPrintService.listIssues("RUN-PR-16").get(0).riskFlag()).isTrue();
        // 原备案与公示证据不改：旧备案终态为 SUPERSEDED，审查明细仍可复算
        assertThat(filingService.getFiling(filingNo).status()).isEqualTo(FilingStatus.SUPERSEDED.name());
        assertThat(filingService.getReviews(filingNo, 1)).isNotEmpty();
    }
}
