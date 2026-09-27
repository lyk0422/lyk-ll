package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 受控量产门禁 H2 测试：过敏原切换凭证门禁主流程与失败分支、
 * 原子核销（失败不消耗凭证）、占用版本推进与 requestId 幂等。
 */
class ControlledProductionIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-02-01T08:00:00Z");

    @Autowired
    private ComplianceService service;

    @Test
    void firstProductionOnLineNeedsNoVoucherAndAdvancesOccupancy() {
        service.registerLine(Fixtures.line("L-01"));
        long filingId = approvedAndFiled("P-01", List.of("MILK"));

        Results.ControlledProductionView view = service.controlledProduction(
                Fixtures.controlledProduction("L-01", filingId, "B-001", "12.5", 1, null)).body();

        assertThat(view.recordId()).isNotNull();
        assertThat(view.occupancyVersion()).isEqualTo(1);
        assertThat(view.voucherId()).isNull();
        assertThat(repository.countProductionRecords(filingId)).isEqualTo(1);

        Results.LineView line = service.getLine("L-01");
        assertThat(line.occupancyVersion()).isEqualTo(2);
        assertThat(line.lastFormulaId()).isEqualTo(service.getFiling(filingId).formulaId());
    }

    @Test
    void switchWithUncoveredAllergenRequiresVoucher() {
        lineWithPreviousBatch("L-02", List.of("MILK"));
        long newFiling = approvedAndFiled("P-02-NEW", List.of());

        // 前批含 MILK 而新批未声明：无凭证 422，线路版本不推进、不写记录。
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-02", newFiling, "B-002", "5", 2, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("CLEANING_VOUCHER_REQUIRED");
                });
        assertThat(service.getLine("L-02").occupancyVersion()).isEqualTo(2);
        assertThat(repository.countProductionRecords(newFiling)).isZero();
    }

    @Test
    void validVoucherCoveringGapAcceptedAndConsumedAtomically() {
        lineWithPreviousBatch("L-03", List.of("MILK"));
        long newFiling = approvedAndFiled("P-03-NEW", List.of());
        long voucherId = confirmVoucher("L-03", List.of("MILK"), 2);

        Results.ControlledProductionView view = service.controlledProduction(
                Fixtures.controlledProduction("L-03", newFiling, "B-003", "8", 2, voucherId))
                .body();

        assertThat(view.voucherId()).isEqualTo(voucherId);
        assertThat(view.occupancyVersion()).isEqualTo(2);
        // 同事务：记录写入、占用版本推进、凭证核销。
        assertThat(repository.countProductionRecords(newFiling)).isEqualTo(1);
        Results.LineView line = service.getLine("L-03");
        assertThat(line.occupancyVersion()).isEqualTo(3);
        assertThat(line.lastFormulaId()).isEqualTo(service.getFiling(newFiling).formulaId());
        Results.VoucherView voucher = service.getVoucher(voucherId);
        assertThat(voucher.status()).isEqualTo("CONSUMED");
        assertThat(voucher.consumedAt()).isNotNull();
    }

    @Test
    void voucherWithInsufficientCoverageRejectedAndNotConsumed() {
        lineWithPreviousBatch("L-04", List.of("MILK", "EGG"));
        long newFiling = approvedAndFiled("P-04-NEW", List.of("EGG"));
        // 缺口为 {MILK}，凭证只覆盖 EGG。
        long voucherId = confirmVoucher("L-04", List.of("EGG"), 2);

        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-04", newFiling, "B-004", "5", 2, voucherId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("VOUCHER_COVERAGE_INSUFFICIENT");
                });
        // 失败不消耗凭证、不推进版本、不写记录。
        assertThat(service.getVoucher(voucherId).status()).isEqualTo("ISSUED");
        assertThat(service.getLine("L-04").occupancyVersion()).isEqualTo(2);
        assertThat(repository.countProductionRecords(newFiling)).isZero();
    }

    @Test
    void voucherExpiredExactlyAtTwoHoursRejected() {
        lineWithPreviousBatch("L-05", List.of("MILK"));
        long newFiling = approvedAndFiled("P-05-NEW", List.of());
        setInstant(T0);
        long voucherId = confirmVoucher("L-05", List.of("MILK"), 2);

        // 恰满 2 小时失效。
        setInstant(T0.plusMillis(ComplianceService.VOUCHER_VALIDITY_MILLIS));
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-05", newFiling, "B-005", "5", 2, voucherId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("VOUCHER_EXPIRED");
                });
        assertThat(service.getVoucher(voucherId).status()).isEqualTo("ISSUED");
        assertThat(repository.countProductionRecords(newFiling)).isZero();
    }

    @Test
    void voucherInvalidatedBySubsequentProductionOnSameLine() {
        lineWithPreviousBatch("L-06", List.of("MILK"));
        long voucherId = confirmVoucher("L-06", List.of("MILK"), 2);

        // 另一笔无需凭证的量产先提交（新批声明了 MILK），占用版本推进，旧凭证失效。
        long midFiling = approvedAndFiled("P-06-MID", List.of("MILK"));
        service.controlledProduction(
                Fixtures.controlledProduction("L-06", midFiling, "B-006-M", "3", 2, null));
        assertThat(service.getVoucher(voucherId).effectiveStatus()).isEqualTo("INVALIDATED");

        long newFiling = approvedAndFiled("P-06-NEW", List.of());
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-06", newFiling, "B-006", "5", 3, voucherId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("VOUCHER_INVALIDATED");
                });
        assertThat(service.getVoucher(voucherId).status()).isEqualTo("ISSUED");
    }

    @Test
    void occupancyVersionMismatchConflicts() {
        service.registerLine(Fixtures.line("L-07"));
        long filingId = approvedAndFiled("P-07", List.of());
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-07", filingId, "B-007", "5", 9, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("OCCUPANCY_VERSION_CONFLICT");
                });
        assertThat(repository.countProductionRecords(filingId)).isZero();
    }

    @Test
    void cancelledOrSuspendedFilingRejectedAndVoucherNotConsumed() {
        lineWithPreviousBatch("L-08", List.of("MILK"));
        long voucherId = confirmVoucher("L-08", List.of("MILK"), 2);

        // 注销备案：422，凭证不消耗。
        long cancelledFiling = approvedAndFiled("P-08-C", List.of());
        service.cancelFiling(Fixtures.filingRef(cancelledFiling));
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-08", cancelledFiling, "B-008", "5", 2, voucherId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("FILING_CANCELLED");
                });
        assertThat(service.getVoucher(voucherId).status()).isEqualTo("ISSUED");

        // 暂停备案（被新版本取代）：422。
        long suspendedFiling = approvedAndFiled("P-08-S", List.of());
        long v2 = service.submitFormula(Fixtures.formula("P-08-S", 2,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(v2));
        service.approveFormula(Fixtures.ref(v2));
        assertThat(service.getFiling(suspendedFiling).status()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-08", suspendedFiling, "B-008S", "5", 2, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("FILING_SUSPENDED");
                });
        assertThat(service.getLine("L-08").occupancyVersion()).isEqualTo(2);
    }

    @Test
    void missingObjectsReturn404() {
        service.registerLine(Fixtures.line("L-09"));
        long filingId = approvedAndFiled("P-09", List.of());
        // 线路不存在 404。
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-MISSING", filingId, "B-009", "5", 1, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("LINE_NOT_FOUND");
                });
        // 备案不存在 404。
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-09", 999999L, "B-009", "5", 1, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("FILING_NOT_FOUND");
                });
        // 凭证不存在 404（前批 MILK 未声明，必须引用凭证）。
        lineWithPreviousBatch("L-09B", List.of("MILK"));
        long newFiling = approvedAndFiled("P-09-NEW", List.of());
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction("L-09B", newFiling, "B-009B", "5", 2, 999999L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("VOUCHER_NOT_FOUND");
                });
    }

    @Test
    void supersetDeclarationNeedsNoVoucher() {
        lineWithPreviousBatch("L-10", List.of("MILK"));
        // 新批声明覆盖前批全部过敏原：无需凭证。
        long newFiling = approvedAndFiled("P-10-NEW", List.of("MILK", "EGG"));
        Results.ControlledProductionView view = service.controlledProduction(
                Fixtures.controlledProduction("L-10", newFiling, "B-010", "5", 2, null)).body();
        assertThat(view.voucherId()).isNull();
        assertThat(service.getLine("L-10").occupancyVersion()).isEqualTo(3);
    }

    @Test
    void idempotentReplayAndFailureDoesNotOccupyKey() {
        service.registerLine(Fixtures.line("L-11"));
        long filingId = approvedAndFiled("P-11", List.of());
        String requestId = Fixtures.requestId();

        Results.ControlledProductionView first = service.controlledProduction(
                Fixtures.controlledProduction(requestId, "L-11", filingId, "B-011", "5", 1, null))
                .body();
        Results.ControlledProductionView replay = service.controlledProduction(
                Fixtures.controlledProduction(requestId, "L-11", filingId, "B-011", "5", 1, null))
                .body();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.recordId()).isEqualTo(first.recordId());
        // 重放不重复推进占用版本、不重复写记录。
        assertThat(service.getLine("L-11").occupancyVersion()).isEqualTo(2);
        assertThat(repository.countProductionRecords(filingId)).isEqualTo(1);

        // 失败不占键：无凭证 422 后，同一 requestId 携带凭证重试按新请求受理。
        lineWithPreviousBatch("L-11B", List.of("MILK"));
        long newFiling = approvedAndFiled("P-11-NEW", List.of());
        String failKey = Fixtures.requestId();
        assertThatThrownBy(() -> service.controlledProduction(
                Fixtures.controlledProduction(failKey, "L-11B", newFiling, "B-011B", "5", 2, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CLEANING_VOUCHER_REQUIRED"));
        long voucherId = confirmVoucher("L-11B", List.of("MILK"), 2);
        Results.ControlledProductionView retried = service.controlledProduction(
                Fixtures.controlledProduction(failKey, "L-11B", newFiling, "B-011B", "5", 2,
                        voucherId)).body();
        assertThat(retried.replayed()).isFalse();
        assertThat(retried.voucherId()).isEqualTo(voucherId);
    }

    @Test
    void historyRecordsAndApprovedSnapshotSurviveSuperseding() {
        service.registerLine(Fixtures.line("L-12"));
        String productCode = "P-12";
        long v1 = service.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(v1));
        service.approveFormula(Fixtures.ref(v1));
        service.upsertAllergens(Fixtures.allergens(v1, List.of("MILK"), null));
        long filingId = service.fileFiling(Fixtures.file(v1)).body().filingId();
        String snapshotBefore = repository.findFormulaById(v1).orElseThrow().approvedSnapshot();

        service.controlledProduction(
                Fixtures.controlledProduction("L-12", filingId, "B-012", "9", 1, null));

        // 批准新版本：旧备案暂停；历史量产记录与批准快照不改。
        long v2 = service.submitFormula(Fixtures.formula(productCode, 2,
                List.of(Fixtures.additive("E202", "0.1")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(v2));
        service.approveFormula(Fixtures.ref(v2));
        assertThat(service.getFiling(filingId).status()).isEqualTo("SUSPENDED");
        assertThat(repository.countProductionRecords(filingId)).isEqualTo(1);
        assertThat(repository.findFormulaById(v1).orElseThrow().approvedSnapshot())
                .isEqualTo(snapshotBefore);
    }

    // ---------------- 场景辅助 ----------------

    /** 登记生产线并量产一批含指定过敏原的产品，占用版本推进到 2。 */
    private void lineWithPreviousBatch(String lineCode, List<String> previousAllergens) {
        service.registerLine(Fixtures.line(lineCode));
        long prevFiling = approvedAndFiled("P-" + lineCode + "-PREV", previousAllergens);
        service.controlledProduction(
                Fixtures.controlledProduction(lineCode, prevFiling, "B-PREV", "10", 1, null));
    }

    private long confirmVoucher(String lineCode, List<String> allergens, int occupancyVersion) {
        return service.confirmCleaning(Fixtures.cleaning(lineCode, allergens,
                clock.nowMillis() - 60_000, occupancyVersion, "operator-1", "reviewer-1"))
                .body().voucherId();
    }

    /** 提交→审查→批准→备案，并按需登记过敏原集合，返回备案主键。 */
    private long approvedAndFiled(String productCode, List<String> allergens) {
        long formulaId = service.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        if (allergens != null) {
            service.upsertAllergens(Fixtures.allergens(formulaId, allergens, null));
        }
        return service.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }
}
