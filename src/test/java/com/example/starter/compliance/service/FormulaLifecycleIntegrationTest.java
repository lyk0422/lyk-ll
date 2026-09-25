package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.engine.ReviewModels.ItemResult;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.web.Requests.DirectAdditiveDto;
import com.example.starter.compliance.web.Requests.IngredientDto;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 配方提交→审查→批准→备案主流程与失败分支 H2 集成测试：
 * 恰等限量、并用比例恰 1、带入折算超限整单拒绝、版本冻结、新版本取代旧备案。
 */
class FormulaLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    /** 提交→审查→批准走完整链路，返回 formulaId。 */
    private long approveFlow(SubmitFormulaRequest request) {
        long formulaId = service.submitFormula(request).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        return formulaId;
    }

    @Test
    void exactLimitPassesAndCanBeFiled() {
        // E202 用量 0.5 恰等 BEV01 限量 0.5：合法，全链路通过并备案 ACTIVE。
        SubmitFormulaRequest request = Fixtures.formula("P-EXACT", 1,
                List.of(Fixtures.additive("E202", "0.5")), List.of());

        long formulaId = approveFlow(request);

        Results.FormulaView formula = service.getFormula(formulaId);
        assertThat(formula.state()).isEqualTo("APPROVED");
        assertThat(formula.reviewPassed()).isTrue();
        ItemResult item = formula.review().items().get(0);
        assertThat(item.additiveCode()).isEqualTo("E202");
        assertThat(item.totalAmount()).isEqualByComparingTo("0.500000");
        assertThat(item.limitAmount()).isEqualByComparingTo("0.500000");
        assertThat(item.usageRatio()).isEqualByComparingTo("1.000000");

        Results.FilingView filing = service.fileFiling(Fixtures.file(formulaId)).body();
        assertThat(filing.status()).isEqualTo("ACTIVE");
        assertThat(service.getFiling(filing.filingId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void sameFunctionCombinedRatioExactlyOnePasses() {
        // 两种防腐剂比例 0.5+0.5 恰等 1：合法。
        SubmitFormulaRequest request = Fixtures.formula("P-GROUP", 1,
                List.of(Fixtures.additive("E202", "0.25"), Fixtures.additive("E211", "0.5")),
                List.of());

        long formulaId = approveFlow(request);

        Results.FormulaView formula = service.getFormula(formulaId);
        assertThat(formula.review().passed()).isTrue();
        assertThat(formula.review().groups()).hasSize(1);
        assertThat(formula.review().groups().get(0).functionCode()).isEqualTo("PRESERVATIVE");
        assertThat(formula.review().groups().get(0).combinedRatio())
                .isEqualByComparingTo("1.000000");
    }

    @Test
    void carriedOverExceedingLimitRejectsWholeOrderWithoutPartialRecords() {
        // ING_SAUCE 占比 0.3 × E202 本底 2.0 = 0.6 > 0.5：带入折算超限，整单拒绝。
        SubmitFormulaRequest request = Fixtures.formula("P-CARRY-FAIL", 1,
                List.of(), List.of(Fixtures.ingredient("ING_SAUCE", "0.3")));

        long formulaId = service.submitFormula(request).body().formulaId();
        Results.ReviewView review = service.reviewFormula(Fixtures.ref(formulaId)).body();

        assertThat(review.state()).isEqualTo("SUBMITTED");
        assertThat(review.report().passed()).isFalse();
        ItemResult violating = review.report().items().stream()
                .filter(i -> "E202".equals(i.additiveCode())).findFirst().orElseThrow();
        assertThat(violating.reason()).isEqualTo("OVER_LIMIT");
        assertThat(violating.carriedAmount()).isEqualByComparingTo("0.600000");

        // 不产生部分后续记录：不能批准、无备案、配方保持 SUBMITTED。
        assertThatThrownBy(() -> service.approveFormula(Fixtures.ref(formulaId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REVIEW_NOT_PASSED"));
        assertThat(service.getFormula(formulaId).state()).isEqualTo("SUBMITTED");
        assertThat(service.getTrajectory("P-CARRY-FAIL").filings()).isEmpty();
    }

    @Test
    void carriedOverWithinLimitPasses() {
        // ING_SAUCE 含 E202 2.0、E211 4.0 g/kg；占比 0.1 → 终产品带入 E202=0.2、E211=0.4，
        // 单项均不超限，防腐剂并用比例 0.2/0.5 + 0.4/1.0 = 0.8 ≤ 1：合法。
        SubmitFormulaRequest request = Fixtures.formula("P-CARRY-OK", 1,
                List.of(), List.of(Fixtures.ingredient("ING_SAUCE", "0.1")));

        long formulaId = approveFlow(request);
        var report = service.getFormula(formulaId).review();
        assertThat(report.passed()).isTrue();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.additiveCode()).isEqualTo("E202");
            assertThat(item.carriedAmount()).isEqualByComparingTo("0.200000");
        });
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.additiveCode()).isEqualTo("E211");
            assertThat(item.carriedAmount()).isEqualByComparingTo("0.400000");
        });
        assertThat(report.groups()).singleElement()
                .satisfies(group -> assertThat(group.combinedRatio())
                        .isEqualByComparingTo("0.800000"));
    }

    @Test
    void approvalFreezesVersionAndRejectsReReview() {
        SubmitFormulaRequest request = Fixtures.formula("P-FROZEN", 1,
                List.of(Fixtures.additive("E202", "0.3")), List.of());
        long formulaId = approveFlow(request);
        long filingId = service.fileFiling(Fixtures.file(formulaId)).body().filingId();

        // 批准后版本冻结：再次审查 409；批准接口对已冻结版本幂等返回。
        assertThatThrownBy(() -> service.reviewFormula(Fixtures.ref(formulaId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("FORMULA_FROZEN"));
        Results.FormulaView secondApprove =
                service.approveFormula(Fixtures.ref(formulaId)).body();
        assertThat(secondApprove.state()).isEqualTo("APPROVED");

        // 备案按批准快照执行：快照中保留批准时的限量 0.5（g/kg），标准后续更新不改变快照。
        Results.FilingView filing = service.getFiling(filingId);
        com.fasterxml.jackson.databind.JsonNode snapshot =
                (com.fasterxml.jackson.databind.JsonNode) filing.snapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.path("limits").path("E202").path("maxAmount").doubleValue())
                .isEqualTo(0.5);
        assertThat(snapshot.path("content").path("version").intValue()).isEqualTo(1);
    }

    @Test
    void approvingNewVersionSuspendsOldFilingInSameTransaction() {
        long v1 = approveFlow(Fixtures.formula("P-SUPERSEDE", 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of()));
        long filingV1 = service.fileFiling(Fixtures.file(v1)).body().filingId();
        assertThat(service.getFiling(filingV1).status()).isEqualTo("ACTIVE");

        // 新版本批准与旧版本备案暂停同一事务完成。
        long v2 = approveFlow(Fixtures.formula("P-SUPERSEDE", 2,
                List.of(Fixtures.additive("E202", "0.1")), List.of()));

        FilingRow old = repository.findFilingById(filingV1).orElseThrow();
        assertThat(old.status()).isEqualTo("SUSPENDED");
        assertThat(old.suspendReason()).isEqualTo("SUPERSEDED");

        // 旧备案释放唯一有效位后，新版本可备案；任意时刻只有一个有效版本。
        Results.FilingView filingV2 = service.fileFiling(Fixtures.file(v2)).body();
        assertThat(filingV2.status()).isEqualTo("ACTIVE");
        assertThat(repository.findEffectiveFilingsByProduct("P-SUPERSEDE")).hasSize(1);
        assertThat(repository.findEffectiveFilingsByProduct("P-SUPERSEDE").get(0).id())
                .isEqualTo(filingV2.filingId());
    }

    @Test
    void duplicateFormulaVersionConflicts() {
        SubmitFormulaRequest first = Fixtures.formula("P-DUP", 1,
                List.of(Fixtures.additive("E202", "0.1")), List.of());
        service.submitFormula(first);
        SubmitFormulaRequest second = Fixtures.formula("P-DUP", 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of());
        assertThatThrownBy(() -> service.submitFormula(second))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void filingBeforeApprovalRejected() {
        long formulaId = service.submitFormula(Fixtures.formula("P-NOAPPROVE", 1,
                List.of(Fixtures.additive("E202", "0.1")), List.of())).body().formulaId();
        assertThatThrownBy(() -> service.fileFiling(Fixtures.file(formulaId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("FORMULA_NOT_APPROVED"));
    }

    @Test
    void unknownAdditiveAndIngredientRejected() {
        SubmitFormulaRequest badAdditive = new SubmitFormulaRequest(Fixtures.requestId(),
                "P-BAD", "P-BAD-产品", "BEV01", 1,
                List.of(new DirectAdditiveDto("E404", new java.math.BigDecimal("0.01"))),
                List.of());
        assertThatThrownBy(() -> service.submitFormula(badAdditive))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ADDITIVE_NOT_FOUND"));

        SubmitFormulaRequest badIngredient = Fixtures.formula("P-BAD2", 1, List.of(),
                List.of(new IngredientDto("ING_404", new java.math.BigDecimal("0.1"))));
        assertThatThrownBy(() -> service.submitFormula(badIngredient))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("INGREDIENT_NOT_FOUND"));
    }
}
