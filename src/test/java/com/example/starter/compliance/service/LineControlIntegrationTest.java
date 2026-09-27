package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.ClearanceCertificateRow;
import com.example.starter.compliance.web.Requests.ConfirmCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 清线门禁主流程与失败分支 H2 测试：
 * 过敏原登记乐观版本、线路占用版本、凭证双人确认与 2 小时边界、受控量产门禁、
 * 备案暂停/注销拒绝、失败不消耗凭证、幂等重放/异参 409。
 */
class LineControlIntegrationTest extends AbstractIntegrationTest {

    private static final String MILK = "ALG_MILK";
    private static final String EGG = "ALG_EGG";
    private static final String PEANUT = "ALG_PEANUT";

    @Autowired
    private LineControlService lineService;

    @Autowired
    private ComplianceService complianceService;

    private long approvedFiling(String productCode, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E300", "0.2")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        return complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }

    private void registerAllergens(String requestId, String productCode, int version,
            List<String> allergens, int expectedVersion, long at) {
        FormulaAllergenRequest request = Fixtures.allergens(requestId, productCode, version,
                allergens, expectedVersion, at);
        lineService.registerFormulaAllergens(request);
    }

    private RegisterLineRequest freshLine(String lineCode, String lastProduct, Integer lastVersion,
            long at) {
        return Fixtures.line(Fixtures.requestId(), lineCode, lineCode + "-线", lastProduct,
                lastVersion, at);
    }

    private ConfirmCertificateRequest certificate(String lineCode, List<String> allergens,
            long clearedAt, long confirmedAt) {
        return Fixtures.certificate(Fixtures.requestId(), "CERT-" + System.nanoTime(), lineCode,
                allergens, "OP-1", "RV-1", clearedAt, confirmedAt);
    }

    @Test
    void allergenRegistrationStartsAtOneAndOptimisticUpdateIncrements() {
        long t0 = 1_800_000_000_000L;
        String requestId = Fixtures.requestId();
        FormulaAllergenRequest first = Fixtures.allergens(requestId, "P-A", 1,
                List.of(MILK, EGG), 0, t0);
        ServiceResult<LineControlResults.FormulaAllerView> created =
                lineService.registerFormulaAllergens(first);
        assertThat(created.body().version()).isEqualTo(1);
        assertThat(created.body().allergens()).containsExactly(EGG, MILK);

        // 同键同参重放首次冻结结果，不重复加版本。
        ServiceResult<LineControlResults.FormulaAllerView> replay =
                lineService.registerFormulaAllergens(first);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().version()).isEqualTo(1);

        // 修改携带 expectedVersion=1，成功变更加 1。
        FormulaAllergenRequest changed = Fixtures.allergens(Fixtures.requestId(), "P-A", 1,
                List.of(PEANUT), 1, t0 + 1000);
        ServiceResult<LineControlResults.FormulaAllerView> updated =
                lineService.registerFormulaAllergens(changed);
        assertThat(updated.body().version()).isEqualTo(2);
        assertThat(updated.body().allergens()).containsExactly(PEANUT);

        // 陈旧 expectedVersion=1 再次提交：409 乐观冲突，版本保持 2。
        FormulaAllergenRequest stale = Fixtures.allergens(Fixtures.requestId(), "P-A", 1,
                List.of(EGG), 1, t0 + 2000);
        assertThatThrownBy(() -> lineService.registerFormulaAllergens(stale))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("OPTIMISTIC_VERSION_CONFLICT"));
        assertThat(lineService.getAllergenRegistration("P-A", 1).version()).isEqualTo(2);
    }

    @Test
    void allergenRegistrationRejectsUnknownCatalogBlankAndDuplicateCreate() {
        long t0 = 1_800_000_000_000L;
        // 合成目录之外的过敏原：400。
        FormulaAllergenRequest unknown = Fixtures.allergens(Fixtures.requestId(), "P-X", 1,
                List.of("ALG_SHELLFISH"), 0, t0);
        assertThatThrownBy(() -> lineService.registerFormulaAllergens(unknown))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("ALLERGEN_NOT_FOUND");
                });

        registerAllergens(Fixtures.requestId(), "P-X", 1, List.of(MILK), 0, t0);
        // 已登记再以新增 expectedVersion=0：409。
        FormulaAllergenRequest duplicate = Fixtures.allergens(Fixtures.requestId(), "P-X", 1,
                List.of(MILK), 0, t0);
        assertThatThrownBy(() -> lineService.registerFormulaAllergens(duplicate))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ALLERGEN_VERSION_EXISTS"));
    }

    @Test
    void switchWithoutLeftoverAllergenNeedsNoCertificate() {
        long t0 = 1_800_000_000_000L;
        long filingV1 = approvedFiling("P-NOLEFT", 1);
        long filingV2 = approvedFiling("P-NOLEFT", 2);
        registerAllergens(Fixtures.requestId(), "P-NOLEFT", 1, List.of(MILK), 0, t0);
        registerAllergens(Fixtures.requestId(), "P-NOLEFT", 2, List.of(MILK, EGG), 0, t0);
        lineService.registerLine(freshLine("L-NOLEFT", "P-NOLEFT", 1, t0));

        // 新批集合包含前批全部过敏原，其他切换不需凭证。
        ControlledProductionRequest request = Fixtures.controlled(Fixtures.requestId(), filingV2,
                "L-NOLEFT", 1, "B-2", "10", null, t0 + 3600_000L);
        ServiceResult<LineControlResults.ControlledProductionView> result =
                lineService.controlledProduction(request);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().occupiedVersion()).isEqualTo(1);

        LineControlResults.LineView line = lineService.getLine("L-NOLEFT");
        assertThat(line.occupiedVersion()).isEqualTo(2);
        assertThat(line.lastProductCode()).isEqualTo("P-NOLEFT");
        assertThat(line.lastFormulaVersion()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-NOLEFT").productions()).hasSize(1);
    }

    @Test
    void leftoverAllergenRequiresValidCertificateAndConsumesItOnce() {
        long t0 = 1_800_000_000_000L;
        long filingV1 = approvedFiling("P-MILK", 1);
        long filingV2 = approvedFiling("P-EGG", 1);
        registerAllergens(Fixtures.requestId(), "P-MILK", 1, List.of(MILK), 0, t0);
        registerAllergens(Fixtures.requestId(), "P-EGG", 1, List.of(EGG), 0, t0);
        lineService.registerLine(freshLine("L-MAIN", "P-MILK", 1, t0));

        long confirmedAt = t0 + 600_000L;
        long certificateId = lineService.confirmCertificate(certificate("L-MAIN",
                List.of(MILK), t0 + 300_000L, confirmedAt)).body().certificateId();

        // 无凭证：422 且凭证保持 ACTIVE（失败不消耗）。
        ControlledProductionRequest withoutCert = Fixtures.controlled(Fixtures.requestId(),
                filingV2, "L-MAIN", 1, "B-X", "10", null, confirmedAt + 1000);
        assertThatRejected(withoutCert, "CLEARANCE_CERTIFICATE_REQUIRED");
        assertThat(lineService.getCertificate(certificateId).status()).isEqualTo("ACTIVE");

        // 携带有效凭证受理成功并核销；占用版本 1→2，最近配方更新为新批。
        ControlledProductionRequest ok = Fixtures.controlled(Fixtures.requestId(), filingV2,
                "L-MAIN", 1, "B-2", "10", certificateId, confirmedAt + 1000);
        ServiceResult<LineControlResults.ControlledProductionView> accepted =
                lineService.controlledProduction(ok);
        assertThat(accepted.body().certificateId()).isEqualTo(certificateId);
        LineControlResults.CertificateView consumed = lineService.getCertificate(certificateId);
        assertThat(consumed.status()).isEqualTo("CONSUMED");

        // 已核销凭证不能重复使用：新批 P-PN（花生）对当前含蛋批次又形成遗留，旧凭证被核销 → 422，
        // 不写第二条记录、不推进版本。
        long filingPeanut = approvedFiling("P-PN", 1);
        registerAllergens(Fixtures.requestId(), "P-PN", 1, List.of(PEANUT), 0, t0);
        ControlledProductionRequest reuse = Fixtures.controlled(Fixtures.requestId(), filingPeanut,
                "L-MAIN", 2, "B-3", "10", certificateId, confirmedAt + 2000);
        assertThatRejected(reuse, "CERTIFICATE_NOT_VALID");
        assertThat(lineService.getLine("L-MAIN").occupiedVersion()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-MAIN").productions()).hasSize(1);
    }

    @Test
    void certificateBoundaryExactlyTwoHoursAndCoverageAndVersionChecks() {
        long t0 = 1_800_000_000_000L;
        long filingV1 = approvedFiling("P-B1", 1);
        long filingV2 = approvedFiling("P-B2", 1);
        registerAllergens(Fixtures.requestId(), "P-B1", 1, List.of(MILK, EGG), 0, t0);
        registerAllergens(Fixtures.requestId(), "P-B2", 1, List.of(PEANUT), 0, t0);
        lineService.registerLine(freshLine("L-B", "P-B1", 1, t0));
        long confirmedAt = t0 + 1000;

        // 覆盖不足（仅清 MILK，遗留 MILK+EGG）：422，不消耗。
        long milkOnly = lineService.confirmCertificate(certificate("L-B", List.of(MILK),
                confirmedAt - 100, confirmedAt)).body().certificateId();
        ControlledProductionRequest insufficient = Fixtures.controlled(Fixtures.requestId(),
                filingV2, "L-B", 1, "B-I", "1", milkOnly, confirmedAt + 1);
        assertThatRejected(insufficient, "CERTIFICATE_COVERAGE_INSUFFICIENT");

        // 恰满 2 小时失效。
        long fullCert = lineService.confirmCertificate(certificate("L-B", List.of(MILK, EGG),
                confirmedAt - 100, confirmedAt)).body().certificateId();
        ControlledProductionRequest atExactlyTwoHours = Fixtures.controlled(
                Fixtures.requestId(), filingV2, "L-B", 1, "B-E", "1", fullCert,
                confirmedAt + 2L * 60L * 60L * 1000L);
        assertThatRejected(atExactlyTwoHours, "CERTIFICATE_EXPIRED");

        // 恰满前一毫秒仍有效：受理成功。
        ControlledProductionRequest justInside = Fixtures.controlled(Fixtures.requestId(),
                filingV2, "L-B", 1, "B-OK", "1", fullCert,
                confirmedAt + 2L * 60L * 60L * 1000L - 1);
        lineService.controlledProduction(justInside);
        assertThat(lineService.getCertificate(fullCert).status()).isEqualTo("CONSUMED");
        assertThat(lineService.getCertificate(milkOnly).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void certificateMustMatchLineAndOccupiedVersionAndTwoOperators() {
        long t0 = 1_800_000_000_000L;
        approvedFiling("P-C1", 1);
        long filingV2 = approvedFiling("P-C2", 1);
        registerAllergens(Fixtures.requestId(), "P-C1", 1, List.of(MILK), 0, t0);
        registerAllergens(Fixtures.requestId(), "P-C2", 1, List.of(EGG), 0, t0);
        lineService.registerLine(freshLine("L-1", "P-C1", 1, t0));
        lineService.registerLine(freshLine("L-2", null, null, t0));

        // 同一人员不能同时作为操作人与复核人：400。
        ConfirmCertificateRequest samePerson = Fixtures.certificate(Fixtures.requestId(),
                "CERT-SAME", "L-1", List.of(MILK), "OP-9", "OP-9", t0, t0 + 1);
        assertThatThrownBy(() -> lineService.confirmCertificate(samePerson))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("OPERATOR_REVIEWER_SAME");
                });

        // 凭证属于 L-2，量产提交到 L-1：422 线路不匹配。
        long wrongLineCert = lineService.confirmCertificate(certificate("L-2", List.of(MILK),
                t0, t0 + 1)).body().certificateId();
        ControlledProductionRequest wrongLine = Fixtures.controlled(Fixtures.requestId(),
                filingV2, "L-1", 1, "B-WL", "1", wrongLineCert, t0 + 2);
        assertThatRejected(wrongLine, "CERTIFICATE_LINE_MISMATCH");

        // 先在 L-1 无遗留地成功量产一次（新批含前批全部过敏原），占用版本 1→2；
        // 随后新凭证固定到占用版本 2，旧版本 1 的提交不再可用。
        long filingEggV2 = filingV2;
        registerAllergens(Fixtures.requestId(), "P-C3", 1, List.of(MILK, EGG), 0, t0);
        long filingC3 = approvedFiling("P-C3", 1);
        lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(), filingC3,
                "L-1", 1, "B-0", "1", null, t0 + 2));

        long versionPinnedCert = lineService.confirmCertificate(certificate("L-1", List.of(EGG),
                t0, t0 + 3)).body().certificateId();
        // 新凭证固定占用版本 2；量产若声明旧版本 1 先撞版本不匹配。
        ControlledProductionRequest staleVersion = Fixtures.controlled(Fixtures.requestId(),
                filingEggV2, "L-1", 1, "B-WV", "1", versionPinnedCert, t0 + 4);
        assertThatThrownBy(() -> lineService.controlledProduction(staleVersion))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("LINE_VERSION_CONFLICT"));
    }

    @Test
    void suspendedOrCancelledFilingAndMissingRegistrationAreRejected() {
        long t0 = 1_800_000_000_000L;
        long filingActive = approvedFiling("P-F1", 1);
        long filingCancelled = approvedFiling("P-F2", 1);
        complianceService.cancelFiling(Fixtures.filingRef(filingCancelled));
        // 全新线路、P-F1 未登记过敏原集合。
        lineService.registerLine(freshLine("L-F", null, null, t0));

        ControlledProductionRequest cancelled = Fixtures.controlled(Fixtures.requestId(),
                filingCancelled, "L-F", 1, "B-C", "1", null, t0 + 1);
        assertThatRejected(cancelled, "FILING_CANCELLED");

        // 备案有效但新批未登记过敏原：422。
        ControlledProductionRequest unregistered = Fixtures.controlled(Fixtures.requestId(),
                filingActive, "L-F", 1, "B-U", "1", null, t0 + 1);
        assertThatRejected(unregistered, "NEW_BATCH_ALLERGEN_NOT_REGISTERED");
        // 失败未推进版本。
        assertThat(lineService.getLine("L-F").occupiedVersion()).isEqualTo(1);
    }

    @Test
    void notFoundAndInvalidParamsMapTo404And400() {
        ControlledProductionRequest missingFiling = Fixtures.controlled(Fixtures.requestId(),
                999_999L, "L-GHOST", 1, "B", "1", null, 1_800_000_000_000L);
        assertThatThrownBy(() -> lineService.controlledProduction(missingFiling))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        lineService.registerLine(freshLine("L-GHOST", null, null, 1_800_000_000_000L));
        ControlledProductionRequest badVersion = Fixtures.controlled(Fixtures.requestId(),
                999_999L, "L-GHOST", 0, "B", "1", null, 1_800_000_000_000L);
        assertThatThrownBy(() -> lineService.controlledProduction(badVersion))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> lineService.getLine("L-UNKNOWN"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void idempotentReplayFreezesResultAndDifferentParamsConflict() {
        long t0 = 1_800_000_000_000L;
        long filingV2 = approvedFiling("P-ID2", 1);
        registerAllergens(Fixtures.requestId(), "P-ID1", 1, List.of(MILK), 0, t0);
        registerAllergens(Fixtures.requestId(), "P-ID2", 1, List.of(EGG), 0, t0);
        lineService.registerLine(freshLine("L-IDEM", "P-ID1", 1, t0));
        long certId = lineService.confirmCertificate(certificate("L-IDEM", List.of(MILK),
                t0, t0 + 1)).body().certificateId();

        ControlledProductionRequest request = Fixtures.controlled("req-controlled-1", filingV2,
                "L-IDEM", 1, "B-IDEM", "8", certId, t0 + 2);
        ServiceResult<LineControlResults.ControlledProductionView> first =
                lineService.controlledProduction(request);
        ServiceResult<LineControlResults.ControlledProductionView> replay =
                lineService.controlledProduction(request);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().productionRecordId())
                .isEqualTo(first.body().productionRecordId());
        // 重放不产生第二条记录、不二次推进版本。
        assertThat(lineService.getLine("L-IDEM").occupiedVersion()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-IDEM").productions()).hasSize(1);

        // 同键异参（改批次号）：409。
        ControlledProductionRequest different = Fixtures.controlled("req-controlled-1", filingV2,
                "L-IDEM", 1, "B-OTHER", "8", certId, t0 + 2);
        assertThatThrownBy(() -> lineService.controlledProduction(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("REQUEST_PARAM_CONFLICT");
                });

        // 业务失败不占键：同一 requestId 先以错误占用版本失败（409，事务回滚），修正后可成功。
        String retryableKey = "req-controlled-retry";
        registerAllergens(Fixtures.requestId(), "P-ID3", 1, List.of(EGG), 0, t0);
        long filingId3 = approvedFiling("P-ID3", 1);
        ControlledProductionRequest badFirst = Fixtures.controlled(retryableKey, filingId3,
                "L-IDEM", 1, "B-R1", "8", null, t0 + 3);
        assertThatThrownBy(() -> lineService.controlledProduction(badFirst))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("LINE_VERSION_CONFLICT"));
        ControlledProductionRequest corrected = Fixtures.controlled(retryableKey, filingId3,
                "L-IDEM", 2, "B-R1", "8", null, t0 + 3);
        ServiceResult<LineControlResults.ControlledProductionView> retried =
                lineService.controlledProduction(corrected);
        assertThat(retried.replayed()).isFalse();
        assertThat(lineService.getLine("L-IDEM").occupiedVersion()).isEqualTo(3);
    }

    private void assertThatRejected(ControlledProductionRequest request, String code) {
        assertThatThrownBy(() -> lineService.controlledProduction(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo(code);
                });
    }
}
