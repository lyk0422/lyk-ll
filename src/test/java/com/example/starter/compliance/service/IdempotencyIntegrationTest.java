package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * requestId 幂等边界 H2 测试：同键同参重放首次结果、同键异参 409、指纹覆盖 requestId 与全部业务字段。
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        String requestId = Fixtures.requestId();
        SubmitFormulaRequest request = Fixtures.formulaWithRequestId(requestId, "P-IDEM", 1,
                List.of(Fixtures.additive("E202", "0.3")), List.of());

        ServiceResult<Results.FormulaView> first = service.submitFormula(request);
        ServiceResult<Results.FormulaView> replay = service.submitFormula(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        // 同键同参返回首次结果：同一配方主键，不产生重复配方。
        assertThat(replay.body().formulaId()).isEqualTo(first.body().formulaId());
        assertThat(repository.findFormulaVersionsByProduct("P-IDEM")).hasSize(1);
    }

    @Test
    void sameKeyDifferentParamsConflicts() {
        String requestId = Fixtures.requestId();
        SubmitFormulaRequest first = Fixtures.formulaWithRequestId(requestId, "P-CONF", 1,
                List.of(Fixtures.additive("E202", "0.3")), List.of());
        service.submitFormula(first);

        // 同一 requestId 携带不同用量：指纹不一致，返回 409，不写入第二个版本。
        SubmitFormulaRequest different = Fixtures.formulaWithRequestId(requestId, "P-CONF", 1,
                List.of(Fixtures.additive("E202", "0.31")), List.of());
        assertThatThrownBy(() -> service.submitFormula(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("REQUEST_PARAM_CONFLICT");
                });
        assertThat(repository.findFormulaVersionsByProduct("P-CONF")).hasSize(1);
    }

    @Test
    void sameKeyDifferentProductConflicts() {
        String requestId = Fixtures.requestId();
        service.submitFormula(Fixtures.formulaWithRequestId(requestId, "P-A", 1,
                List.of(Fixtures.additive("E202", "0.1")), List.of()));
        assertThatThrownBy(() -> service.submitFormula(
                Fixtures.formulaWithRequestId(requestId, "P-B", 1,
                        List.of(Fixtures.additive("E202", "0.1")), List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REQUEST_PARAM_CONFLICT"));
    }

    @Test
    void filingAndCancelReplaySameOutcome() {
        long formulaId = submitReviewedApproved("P-IDEM-FILE");
        String fileRequestId = Fixtures.requestId();
        FileFilingRequest fileRequest = new FileFilingRequest(fileRequestId, formulaId);

        Results.FilingView first = service.fileFiling(fileRequest).body();
        Results.FilingView replay = service.fileFiling(fileRequest).body();
        assertThat(replay.filingId()).isEqualTo(first.filingId());
        assertThat(repository.findFilingsByProduct("P-IDEM-FILE")).hasSize(1);

        String cancelRequestId = Fixtures.requestId();
        service.cancelFiling(Fixtures.filingRef(cancelRequestId, first.filingId()));
        Results.FilingView cancelReplay =
                service.cancelFiling(Fixtures.filingRef(cancelRequestId, first.filingId())).body();
        assertThat(cancelReplay.replayed()).isTrue();
        assertThat(cancelReplay.status()).isEqualTo("CANCELLED");
    }

    @Test
    void missingRequestIdRejected() {
        SubmitFormulaRequest request = Fixtures.formulaWithRequestId(" ", "P-NOKEY", 1,
                List.of(Fixtures.additive("E202", "0.1")), List.of());
        assertThatThrownBy(() -> service.submitFormula(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REQUEST_ID_REQUIRED"));
    }

    private long submitReviewedApproved(String productCode) {
        long formulaId = service.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        return formulaId;
    }
}
