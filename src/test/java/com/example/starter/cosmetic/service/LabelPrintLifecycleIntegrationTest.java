package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.BandDigestItem;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.DestroyResponse;
import com.example.starter.cosmetic.web.dto.DrawLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawRecordResponse;
import com.example.starter.cosmetic.web.dto.DrawResponse;
import com.example.starter.cosmetic.web.dto.ExpectedDigestResponse;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包装标签印次台账生命周期 H2 集成测试：
 * 登记（成人/儿童逐档摘要）、领用余额与包装批归属、销毁留证、
 * 备案换版/注销联动禁用与风险关联、幂等重放与同键异参 409、张数守恒。
 */
class LabelPrintLifecycleIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private LabelPrintService labelPrintService;

    // ============================ 合成场景辅助 ============================

    /** 成人备案提交并公示期满生效（时钟推进 11 天，支持同用例内多次调用）。 */
    private String makeAdultEffective(String reqId, String product) {
        FilingResultResponse r = filingService.submit(FilingFixtures.ordinarySubmit(reqId, product));
        clock.setInstant(clock.instant().plus(11, java.time.temporal.ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
        return r.filing().filingNo();
    }

    /** 跨档儿童备案提交并公示期满生效（时钟推进 11 天）。 */
    private String makeChildEffective(String reqId, String product) {
        FilingResultResponse r = filingService.submit(FilingFixtures.childSubmitCrossBand(reqId, product));
        clock.setInstant(clock.instant().plus(11, java.time.temporal.ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
        return r.filing().filingNo();
    }

    /** 按当前有效备案的期望摘要构造登记请求。 */
    private RegisterPrintRunRequest registerReq(String reqId, String runNo, String product, int count) {
        ExpectedDigestResponse exp = labelPrintService.expectedDigest(product);
        List<BandDigestItem> bands = exp.bandDigests() == null ? null
                : exp.bandDigests().entrySet().stream()
                .map(e -> new BandDigestItem(e.getKey(), e.getValue())).toList();
        return new RegisterPrintRunRequest(reqId, runNo, product, count, exp.labelDigest(), bands, null);
    }

    private PrintRunResponse register(String reqId, String runNo, String product, int count) {
        return labelPrintService.register(registerReq(reqId, runNo, product, count)).printRun();
    }

    // ============================ 登记 ============================

    @Test
    void registerAdultPrintRunThenDrawConservesCounts() {
        makeAdultEffective("REQ-F-1", "P-LP-1");
        PrintRunResponse run = register("REQ-PR-1", "PR-0001", "P-LP-1", 100);
        assertThat(run.status()).isEqualTo("ACTIVE");
        assertThat(run.version()).isEqualTo(1);
        assertThat(run.printedCount()).isEqualTo(100);
        assertThat(run.bandDigests()).isNull();
        assertThat(run.filingNo()).isEqualTo("SYN-F-000001");
        assertThat(run.filingVersionNo()).isEqualTo(1);

        DrawResponse draw = labelPrintService.draw("PR-0001",
                new DrawLabelsRequest("REQ-D-1", "BATCH-01", 60, 1, null));
        assertThat(draw.printRunVersion()).isEqualTo(2);
        assertThat(draw.remainingAfter()).isEqualTo(40);
        assertThat(draw.idempotentReplay()).isFalse();

        PrintRunResponse after = labelPrintService.getPrintRun("PR-0001");
        assertThat(after.drawnCount()).isEqualTo(60);
        assertThat(after.destroyedCount()).isEqualTo(0);
        assertThat(after.remainingCount()).isEqualTo(40);
        assertThat(after.disabledRemainingCount()).isEqualTo(0);
        // 张数守恒：印刷总数 = 已领 + 已毁 + 剩余
        assertThat(after.drawnCount() + after.destroyedCount() + after.remainingCount())
                .isEqualTo(after.printedCount());
    }

    @Test
    void registerChildPrintRunFreezesPerBandDigests() {
        makeChildEffective("REQ-F-2", "P-LP-2");
        ExpectedDigestResponse exp = labelPrintService.expectedDigest("P-LP-2");
        assertThat(exp.bandDigests()).containsKeys("BAND_0_36", "BAND_37_144");

        PrintRunResponse run = register("REQ-PR-2", "PR-0002", "P-LP-2", 50);
        assertThat(run.bandDigests())
                .containsEntry("BAND_0_36", exp.bandDigests().get("BAND_0_36"))
                .containsEntry("BAND_37_144", exp.bandDigests().get("BAND_37_144"));
        assertThat(run.labelDigest()).isEqualTo(exp.labelDigest());
    }

    @Test
    void registerChildRejectsMissingBand() {
        makeChildEffective("REQ-F-3", "P-LP-3");
        ExpectedDigestResponse exp = labelPrintService.expectedDigest("P-LP-3");
        // 只提供 0~36 月一档：缺 37~144 月档
        RegisterPrintRunRequest request = new RegisterPrintRunRequest("REQ-PR-3", "PR-0003",
                "P-LP-3", 50, exp.labelDigest(),
                List.of(new BandDigestItem("BAND_0_36", exp.bandDigests().get("BAND_0_36"))), null);

        assertThatThrownBy(() -> labelPrintService.register(request))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> {
                    assertThat(e.code()).isEqualTo("BAND_MISSING");
                    assertThat(e.violations()).anyMatch(v -> v.contains("BAND_37_144"));
                });
        assertThatThrownBy(() -> labelPrintService.getPrintRun("PR-0003"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void registerChildRejectsWrongBandDigest() {
        makeChildEffective("REQ-F-4", "P-LP-4");
        ExpectedDigestResponse exp = labelPrintService.expectedDigest("P-LP-4");
        RegisterPrintRunRequest request = new RegisterPrintRunRequest("REQ-PR-4", "PR-0004",
                "P-LP-4", 50, exp.labelDigest(),
                List.of(new BandDigestItem("BAND_0_36", exp.bandDigests().get("BAND_0_36")),
                        new BandDigestItem("BAND_37_144", "0".repeat(64))), null);

        assertThatThrownBy(() -> labelPrintService.register(request))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("BAND_DIGEST_MISMATCH"));
    }

    @Test
    void registerRejectsWrongLabelDigest() {
        makeAdultEffective("REQ-F-5", "P-LP-5");
        RegisterPrintRunRequest request = new RegisterPrintRunRequest("REQ-PR-5", "PR-0005",
                "P-LP-5", 100, "f".repeat(64), null, null);

        assertThatThrownBy(() -> labelPrintService.register(request))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("LABEL_DIGEST_MISMATCH"));
    }

    @Test
    void registerRejectsNonEffectiveFiling() {
        // 仅提交（公示中，未生效）：不能登记印次
        filingService.submit(FilingFixtures.ordinarySubmit("REQ-F-6", "P-LP-6"));
        assertThatThrownBy(() -> labelPrintService.expectedDigest("P-LP-6"))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("NO_EFFECTIVE_FILING"));
        assertThatThrownBy(() -> labelPrintService.register(new RegisterPrintRunRequest(
                "REQ-PR-6", "PR-0006", "P-LP-6", 100, "a".repeat(64), null, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("NO_EFFECTIVE_FILING"));
    }

    @Test
    void registerDuplicatePrintRunNoRejected() {
        makeAdultEffective("REQ-F-7", "P-LP-7");
        register("REQ-PR-7", "PR-0007", "P-LP-7", 100);
        // 同印次号唯一：不同 requestId 再登记同号触发唯一约束（HTTP 层映射 409）
        assertThatThrownBy(() -> register("REQ-PR-7B", "PR-0007", "P-LP-7", 200))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(labelPrintService.getPrintRun("PR-0007").printedCount()).isEqualTo(100);
    }

    // ============================ 领用 ============================

    @Test
    void drawOverBalanceRejectedExactDrawOutAllowed() {
        makeAdultEffective("REQ-F-8", "P-LP-8");
        register("REQ-PR-8", "PR-0008", "P-LP-8", 100);
        labelPrintService.draw("PR-0008", new DrawLabelsRequest("REQ-D-8A", "BATCH-8", 60, 1, null));

        // 超未领余额拒绝
        assertThatThrownBy(() -> labelPrintService.draw("PR-0008",
                new DrawLabelsRequest("REQ-D-8B", "BATCH-8", 41, 2, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE"));
        // 恰领完允许
        DrawResponse last = labelPrintService.draw("PR-0008",
                new DrawLabelsRequest("REQ-D-8C", "BATCH-8", 40, 2, null));
        assertThat(last.remainingAfter()).isZero();
        // 领完后余额为 0，再领拒绝
        assertThatThrownBy(() -> labelPrintService.draw("PR-0008",
                new DrawLabelsRequest("REQ-D-8D", "BATCH-8", 1, 3, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE"));
        PrintRunResponse after = labelPrintService.getPrintRun("PR-0008");
        assertThat(after.drawnCount()).isEqualTo(100);
        assertThat(after.remainingCount()).isZero();
    }

    @Test
    void drawRejectsCrossProductBatch() {
        makeAdultEffective("REQ-F-9A", "P-LP-9A");
        makeAdultEffective("REQ-F-9B", "P-LP-9B");
        register("REQ-PR-9A", "PR-0009A", "P-LP-9A", 100);
        register("REQ-PR-9B", "PR-0009B", "P-LP-9B", 100);

        // 产品 A 首次使用批号 BATCH-X：登记所属产品 A
        labelPrintService.draw("PR-0009A", new DrawLabelsRequest("REQ-D-9A", "BATCH-X", 10, 1, null));
        // 产品 B 借用同一批号：跨产品借用拒绝
        assertThatThrownBy(() -> labelPrintService.draw("PR-0009B",
                new DrawLabelsRequest("REQ-D-9B", "BATCH-X", 10, 1, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("CROSS_PRODUCT_BATCH"));
        // 产品 B 使用自己的新批号可以领用
        DrawResponse ok = labelPrintService.draw("PR-0009B",
                new DrawLabelsRequest("REQ-D-9C", "BATCH-Y", 10, 1, null));
        assertThat(ok.remainingAfter()).isEqualTo(90);
    }

    @Test
    void multipleDrawsPerBatchAreTraceable() {
        makeAdultEffective("REQ-F-10", "P-LP-10");
        register("REQ-PR-10", "PR-0010", "P-LP-10", 100);
        labelPrintService.draw("PR-0010", new DrawLabelsRequest("REQ-D-10A", "BATCH-T", 10, 1, null));
        labelPrintService.draw("PR-0010", new DrawLabelsRequest("REQ-D-10B", "BATCH-T", 20, 2, null));
        labelPrintService.draw("PR-0010", new DrawLabelsRequest("REQ-D-10C", "BATCH-T", 30, 3, null));

        List<DrawRecordResponse> draws = labelPrintService.listDraws("PR-0010");
        assertThat(draws).hasSize(3);
        assertThat(draws).allMatch(d -> d.batchNo().equals("BATCH-T"));
        assertThat(draws).allMatch(d -> !d.riskFlag());
        assertThat(draws.stream().mapToInt(DrawRecordResponse::quantity).sum()).isEqualTo(60);
        assertThat(labelPrintService.getPrintRun("PR-0010").remainingCount()).isEqualTo(40);
    }

    @Test
    void drawStaleExpectedVersionConflict409() {
        makeAdultEffective("REQ-F-11", "P-LP-11");
        register("REQ-PR-11", "PR-0011", "P-LP-11", 100);
        assertThatThrownBy(() -> labelPrintService.draw("PR-0011",
                new DrawLabelsRequest("REQ-D-11", "BATCH-11", 10, 5, null)))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
        assertThat(labelPrintService.getPrintRun("PR-0011").version()).isEqualTo(1);
    }

    // ============================ 销毁 ============================

    @Test
    void destroyReducesRemainingAndRejectsOverDestroy() {
        makeAdultEffective("REQ-F-12", "P-LP-12");
        register("REQ-PR-12", "PR-0012", "P-LP-12", 100);
        labelPrintService.draw("PR-0012", new DrawLabelsRequest("REQ-D-12", "BATCH-12", 60, 1, null));

        DestroyResponse destroyed = labelPrintService.destroy("PR-0012",
                new DestroyLabelsRequest("REQ-X-12A", 30, 2, null));
        assertThat(destroyed.remainingAfter()).isEqualTo(10);
        assertThat(destroyed.printRunVersion()).isEqualTo(3);

        // 已领 60 张不能销毁退回余额：超未领余额 10 拒绝
        assertThatThrownBy(() -> labelPrintService.destroy("PR-0012",
                new DestroyLabelsRequest("REQ-X-12B", 11, 3, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("INSUFFICIENT_REMAINING"));

        labelPrintService.destroy("PR-0012", new DestroyLabelsRequest("REQ-X-12C", 10, 3, null));
        PrintRunResponse after = labelPrintService.getPrintRun("PR-0012");
        assertThat(after.drawnCount()).isEqualTo(60);
        assertThat(after.destroyedCount()).isEqualTo(40);
        assertThat(after.remainingCount()).isZero();
        assertThat(after.drawnCount() + after.destroyedCount() + after.remainingCount())
                .isEqualTo(after.printedCount());
        // 销毁留证
        Integer destroyRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM label_destroy WHERE print_run_no = 'PR-0012'", Integer.class);
        assertThat(destroyRows).isEqualTo(2);
    }

    // ============================ 备案联动禁用 ============================

    @Test
    void simpleChangeDisablesOldPrintRunAndFlagsRisk() {
        makeAdultEffective("REQ-F-13", "P-LP-13");
        register("REQ-PR-13", "PR-0013", "P-LP-13", 100);
        labelPrintService.draw("PR-0013", new DrawLabelsRequest("REQ-D-13", "BATCH-13", 60, 1, null));

        // 包装文字简易变更：新修订即时生效，旧印次剩余标签原子禁用
        filingService.change("P-LP-13", FilingFixtures.packagingChange("REQ-C-13", "合成新包装文字V2"));

        PrintRunResponse disabled = labelPrintService.getPrintRun("PR-0013");
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.version()).isEqualTo(3);
        assertThat(disabled.disabledReason()).contains("简易变更");
        // 禁用剩余单独区分：100 = 60 已领 + 0 已毁 + 40 禁用剩余
        assertThat(disabled.remainingCount()).isEqualTo(40);
        assertThat(disabled.disabledRemainingCount()).isEqualTo(40);

        // 已领凭据只追加风险关联，不抹历史
        List<DrawRecordResponse> draws = labelPrintService.listDraws("PR-0013");
        assertThat(draws).hasSize(1);
        assertThat(draws.get(0).riskFlag()).isTrue();
        assertThat(draws.get(0).riskNote()).contains("已失效");
        assertThat(draws.get(0).quantity()).isEqualTo(60);

        // 禁用印次不允许复活：领用拒绝
        assertThatThrownBy(() -> labelPrintService.draw("PR-0013",
                new DrawLabelsRequest("REQ-D-13B", "BATCH-13", 40, 3, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("PRINT_RUN_DISABLED"));
        // 禁用剩余可登记销毁，但状态不复活
        labelPrintService.destroy("PR-0013", new DestroyLabelsRequest("REQ-X-13", 40, 3, null));
        PrintRunResponse after = labelPrintService.getPrintRun("PR-0013");
        assertThat(after.status()).isEqualTo("DISABLED");
        assertThat(after.remainingCount()).isZero();
        assertThat(after.disabledRemainingCount()).isZero();

        // 换版后可按新修订登记新印次
        ExpectedDigestResponse exp = labelPrintService.expectedDigest("P-LP-13");
        assertThat(exp.filingVersionNo()).isEqualTo(2);
        PrintRunResponse newRun = register("REQ-PR-13B", "PR-0013B", "P-LP-13", 80);
        assertThat(newRun.filingVersionNo()).isEqualTo(2);
        assertThat(newRun.status()).isEqualTo("ACTIVE");
    }

    @Test
    void substantiveChangeEffectuationDisablesOldPrintRun() {
        makeAdultEffective("REQ-F-14", "P-LP-14");
        register("REQ-PR-14", "PR-0014", "P-LP-14", 100);
        labelPrintService.draw("PR-0014", new DrawLabelsRequest("REQ-D-14", "BATCH-14", 60, 1, null));

        // 实质变更（新增功效宣称）：重新审查公示，期满生效后旧印次禁用
        filingService.change("P-LP-14", new ChangeFilingRequest("REQ-C-14", null, null,
                List.of("MOISTURIZING", "REFRESHING"), null, null, null, null, null, null, null));
        clock.setInstant(clock.instant().plus(11, java.time.temporal.ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);

        PrintRunResponse disabled = labelPrintService.getPrintRun("PR-0014");
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.disabledReason()).contains("实质变更");
        assertThat(disabled.disabledRemainingCount()).isEqualTo(40);
        assertThat(labelPrintService.listDraws("PR-0014").get(0).riskFlag()).isTrue();
        // 新旧修订竞争后：旧印次不得继续领用
        assertThatThrownBy(() -> labelPrintService.draw("PR-0014",
                new DrawLabelsRequest("REQ-D-14B", "BATCH-14", 10, 3, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("PRINT_RUN_DISABLED"));
    }

    @Test
    void withdrawDisablesPrintRunAndFlagsRisk() {
        String filingNo = makeAdultEffective("REQ-F-15", "P-LP-15");
        register("REQ-PR-15", "PR-0015", "P-LP-15", 100);
        labelPrintService.draw("PR-0015", new DrawLabelsRequest("REQ-D-15", "BATCH-15", 60, 1, null));

        filingService.withdraw(filingNo, "REQ-W-15", "合成注销原因");

        PrintRunResponse disabled = labelPrintService.getPrintRun("PR-0015");
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.disabledReason()).contains("注销");
        assertThat(disabled.disabledRemainingCount()).isEqualTo(40);
        assertThat(labelPrintService.listDraws("PR-0015").get(0).riskFlag()).isTrue();
        assertThatThrownBy(() -> labelPrintService.draw("PR-0015",
                new DrawLabelsRequest("REQ-D-15B", "BATCH-15", 10, 3, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e ->
                        assertThat(e.code()).isEqualTo("PRINT_RUN_DISABLED"));
    }

    // ============================ 幂等 ============================

    @Test
    void idempotentRegisterReplaysAndMismatchConflicts() {
        makeAdultEffective("REQ-F-16", "P-LP-16");
        RegisterPrintRunRequest request = registerReq("REQ-PR-16", "PR-0016", "P-LP-16", 100);
        PrintRunResultResponse first = labelPrintService.register(request);
        PrintRunResultResponse replay = labelPrintService.register(request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.printRun().printRunNo()).isEqualTo("PR-0016");
        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM label_print_run WHERE print_run_no = 'PR-0016'", Integer.class);
        assertThat(runCount).isEqualTo(1);

        // 同键异参：409
        RegisterPrintRunRequest mismatched = new RegisterPrintRunRequest("REQ-PR-16", "PR-0016X",
                "P-LP-16", 200, request.labelDigest(), null, null);
        assertThatThrownBy(() -> labelPrintService.register(mismatched))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.code()).isEqualTo("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void idempotentDrawReplaysAndFailureDoesNotOccupyKey() {
        makeAdultEffective("REQ-F-17", "P-LP-17");
        register("REQ-PR-17", "PR-0017", "P-LP-17", 100);

        // 失败不占键：超余额领用 422 后，同键换合法参数可成功
        assertThatThrownBy(() -> labelPrintService.draw("PR-0017",
                new DrawLabelsRequest("REQ-D-17", "BATCH-17", 999, 1, null)))
                .isInstanceOf(UnprocessableException.class);
        DrawResponse first = labelPrintService.draw("PR-0017",
                new DrawLabelsRequest("REQ-D-17", "BATCH-17", 60, 1, null));
        assertThat(first.idempotentReplay()).isFalse();

        // 同键同参重放：不重复扣减
        DrawResponse replay = labelPrintService.draw("PR-0017",
                new DrawLabelsRequest("REQ-D-17", "BATCH-17", 60, 1, null));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(labelPrintService.getPrintRun("PR-0017").drawnCount()).isEqualTo(60);
        assertThat(labelPrintService.listDraws("PR-0017")).hasSize(1);

        // 同键异参：409
        assertThatThrownBy(() -> labelPrintService.draw("PR-0017",
                new DrawLabelsRequest("REQ-D-17", "BATCH-17", 30, 1, null)))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.code()).isEqualTo("IDEMPOTENCY_PARAM_MISMATCH"));
    }
}
