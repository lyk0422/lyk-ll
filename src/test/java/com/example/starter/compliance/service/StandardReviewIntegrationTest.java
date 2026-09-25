package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 标准更新→复审观察→90 个 UTC 自然日复审/逾期暂停、注销终态与量产上报 H2 集成测试。
 */
class StandardReviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    private static final LocalDate DAY_0 = LocalDate.of(2026, 1, 1);

    private long filedFormula(String productCode, String e202Amount) {
        SubmitFormulaRequest request = Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", e202Amount)), List.of());
        long formulaId = service.submitFormula(request).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        return formulaId;
    }

    @Test
    void standardUpdatePutsAffectedFilingIntoReviewWatch() {
        setUtcDate(DAY_0);
        long formulaId = filedFormula("P-WATCH", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();

        // 限量 0.5 → 0.4：该备案配方直接使用 E202，受影响进入复审观察，起始 UTC 日为今日。
        Results.StandardView standard = service.updateStandard(
                Fixtures.standard("E202", "BEV01", "0.4")).body();
        assertThat(standard.watchFilingIds()).containsExactly(filingId);

        Results.FilingView filing = service.getFiling(filingId);
        assertThat(filing.status()).isEqualTo("REVIEW_WATCH");
        assertThat(filing.watchStartDay()).isEqualTo(DAY_0.toEpochDay());
        assertThat(filing.deadlineDay()).isEqualTo(DAY_0.plusDays(90).toEpochDay());
        assertThat(filing.daysRemaining()).isEqualTo(90L);
    }

    @Test
    void reviewOnExactlyNinetiethDayIsAcceptedAndRestoresActive() {
        setUtcDate(DAY_0);
        long formulaId = filedFormula("P-D90", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();
        service.updateStandard(Fixtures.standard("E202", "BEV01", "0.4"));

        // 恰第 90 个 UTC 自然日提交复审受理；0.3 ≤ 新限量 0.4，复审通过恢复 ACTIVE。
        setUtcDate(DAY_0.plusDays(90));
        Results.FilingReviewView review =
                service.reviewFiling(Fixtures.filingRef(filingId)).body();
        assertThat(review.passed()).isTrue();
        assertThat(review.status()).isEqualTo("ACTIVE");
        assertThat(service.getFiling(filingId).status()).isEqualTo("ACTIVE");
        assertThat(service.getFiling(filingId).watchStartDay()).isNull();
    }

    @Test
    void reviewOnNinetyFirstDayAutoSuspends() {
        setUtcDate(DAY_0);
        long formulaId = filedFormula("P-D91", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();
        service.updateStandard(Fixtures.standard("E202", "BEV01", "0.4"));

        // 第 91 日：逾期未复审，自动暂停备案，复审请求首次结果状态码即 409。
        setUtcDate(DAY_0.plusDays(91));
        ServiceResult<Results.FilingReviewView> overdue =
                service.reviewFiling(Fixtures.filingRef(filingId));
        assertThat(overdue.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(overdue.body().passed()).isFalse();
        assertThat(service.getFiling(filingId).status()).isEqualTo("SUSPENDED");
        assertThat(service.getFiling(filingId).suspendReason()).isEqualTo("OVERDUE");
        // 暂停后释放唯一有效位。
        assertThat(repository.findEffectiveFilingsByProduct("P-D91")).isEmpty();
    }

    @Test
    void sweepSuspendsOverdueFilings() {
        setUtcDate(DAY_0);
        long formulaId = filedFormula("P-SWEEP", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();
        service.updateStandard(Fixtures.standard("E202", "BEV01", "0.4"));

        setUtcDate(DAY_0.plusDays(90));
        assertThat(service.sweepOverdue().suspendedFilingIds()).isEmpty();

        setUtcDate(DAY_0.plusDays(91));
        Results.SweepView sweep = service.sweepOverdue();
        assertThat(sweep.suspendedFilingIds()).containsExactly(filingId);
        assertThat(service.getFiling(filingId).status()).isEqualTo("SUSPENDED");
    }

    @Test
    void reviewFailureUnderNewStandardSuspendsFiling() {
        setUtcDate(DAY_0);
        // 配方 E202 0.5 恰等旧限量 0.5 合法；标准收紧到 0.4 后复算 0.5 > 0.4，复审不通过暂停。
        long formulaId = filedFormula("P-RV-FAIL", "0.5");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();
        service.updateStandard(Fixtures.standard("E202", "BEV01", "0.4"));

        setUtcDate(DAY_0.plusDays(30));
        Results.FilingReviewView review =
                service.reviewFiling(Fixtures.filingRef(filingId)).body();
        assertThat(review.passed()).isFalse();
        assertThat(review.status()).isEqualTo("SUSPENDED");
        assertThat(service.getFiling(filingId).suspendReason()).isEqualTo("REVIEW_FAILED");
        assertThat(repository.findEffectiveFilingsByProduct("P-RV-FAIL")).isEmpty();
    }

    @Test
    void productionAllowedDuringWatchWindowAndRejectedAfterOverdue() {
        setUtcDate(DAY_0);
        long formulaId = filedFormula("P-PROD", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();
        service.updateStandard(Fixtures.standard("E202", "BEV01", "0.4"));

        // 观察期内（第 90 日）量产上报受理。
        setUtcDate(DAY_0.plusDays(90));
        service.reportProduction(Fixtures.production(filingId, "B-1", "100"));
        assertThat(repository.countProductionRecords(filingId)).isEqualTo(1L);

        // 逾期后上报返回首次结果 409：不写量产记录，且备案自动暂停；同键重放结果一致。
        setUtcDate(DAY_0.plusDays(91));
        var overdueProduction = Fixtures.production(filingId, "B-2", "200");
        ServiceResult<Results.ProductionView> rejected =
                service.reportProduction(overdueProduction);
        assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT.value());
        ServiceResult<Results.ProductionView> rejectedReplay =
                service.reportProduction(overdueProduction);
        assertThat(rejectedReplay.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(rejectedReplay.replayed()).isTrue();
        assertThat(repository.countProductionRecords(filingId)).isEqualTo(1L);
        assertThat(service.getFiling(filingId).status()).isEqualTo("SUSPENDED");
    }

    @Test
    void cancelIsTerminalAndRejectsProductionAndRepeatCancellationIsIdempotent() {
        long formulaId = filedFormula("P-CANCEL", "0.3");
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();

        Results.FilingView first =
                service.cancelFiling(Fixtures.filingRef(filingId)).body();
        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(first.finalizedAt()).isNotNull();

        // 重复注销（即使换新 requestId）返回幂等成功，状态不变。
        Results.FilingView second =
                service.cancelFiling(Fixtures.filingRef(filingId)).body();
        assertThat(second.status()).isEqualTo("CANCELLED");
        assertThat(second.finalizedAt()).isEqualTo(first.finalizedAt());

        // 注销后量产记录上报一律拒绝（410），不写入记录。
        assertThatThrownBy(() ->
                service.reportProduction(Fixtures.production(filingId, "B-X", "10")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.GONE));
        assertThat(repository.countProductionRecords(filingId)).isZero();

        // 注销终态不可复审。
        assertThatThrownBy(() -> service.reviewFiling(Fixtures.filingRef(filingId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.GONE));
    }

    @Test
    void unaffectedFilingStaysActiveAfterStandardUpdate() {
        setUtcDate(DAY_0);
        // 配方仅用 E211，更新 E202 标准不影响该备案（终产品不含 E202）。
        SubmitFormulaRequest request = Fixtures.formula("P-UNAFFECTED", 1,
                List.of(Fixtures.additive("E211", "0.5")), List.of());
        long formulaId = service.submitFormula(request).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();

        Results.StandardView standard = service.updateStandard(
                Fixtures.standard("E202", "BEV01", "0.4")).body();
        assertThat(standard.watchFilingIds()).doesNotContain(filingId);
        assertThat(service.getFiling(filingId).status()).isEqualTo("ACTIVE");
    }
}
