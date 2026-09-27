package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.ClearanceCertificateRow;
import com.example.starter.compliance.repo.ProductionLineRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 换配方清线门禁主流程与失败分支 H2 集成测试：
 * 登记（版本/占用版本）、双人凭证 2 小时窗口、过敏原覆盖判定、原子受理与核销、
 * 备案暂停/注销、幂等重放与 400/404/422 语义；失败不消耗凭证、不推进占用版本。
 */
class ClearanceFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final long HOUR = 3_600_000L;
    private static final String PEANUT = "AL_PEANUT";
    private static final String MILK = "AL_MILK";
    private static final String EGG = "AL_EGG";

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private ClearanceService clearanceService;

    /** 提交→审查→批准→备案（ACTIVE）同一产品某版本，返回 [formulaId, filingId]。 */
    private long[] approvedAndFiled(String product, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(product, version,
                List.of(Fixtures.additive("E202", "0.3")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        long filingId = complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
        return new long[] {formulaId, filingId};
    }

    private void registerLine(String lineCode) {
        clearanceService.registerLine(Fixtures.line(lineCode, lineCode + "-线"));
    }

    private void registerAllergens(long formulaId, List<String> allergens) {
        clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(formulaId, null, allergens));
    }

    private long confirmCertificate(String lineCode, long confirmedAt, List<String> covered) {
        return clearanceService.confirmClearance(Fixtures.clearance(lineCode, "OP-1", "RV-1",
                confirmedAt - HOUR, confirmedAt, covered)).body().certificateId();
    }

    // ---------------- 主流程 ----------------

    @Test
    void firstBatchOnFreshLineNeedsNoCertificateAndAdvancesOccupiedVersion() {
        long[] v1 = approvedAndFiled("P-FIRST", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-FIRST");

        Results.ControlledProductionView view = clearanceService.declareControlledProduction(
                Fixtures.controlled("L-FIRST", v1[1], "B-1", 1, T0.toEpochMilli(), null)).body();

        assertThat(view.certificateId()).isNull();
        assertThat(view.occupiedVersion()).isEqualTo(1);
        ProductionLineRow line = repository.findProductionLine("L-FIRST").orElseThrow();
        assertThat(line.occupiedVersion()).isEqualTo(2);
        assertThat(line.lastFormulaId()).isEqualTo(v1[0]);
        assertThat(clearanceService.listControlledProductions("L-FIRST")).hasSize(1);
    }

    @Test
    void repeatSameFormulaVersionSwitchNeedsNoCertificate() {
        long[] v1 = approvedAndFiled("P-SAME", 1);
        registerAllergens(v1[0], List.of(PEANUT, MILK));
        registerLine("L-SAME");

        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-SAME", v1[1], "B-1", 1, T0.toEpochMilli(), null));
        // 同一配方版本连续生产：前批过敏原均被本批声明，无需凭证；占用版本推进到 2。
        Results.ControlledProductionView second = clearanceService.declareControlledProduction(
                Fixtures.controlled("L-SAME", v1[1], "B-2", 2,
                        T0.plusMillis(HOUR).toEpochMilli(), null)).body();
        assertThat(second.certificateId()).isNull();
        assertThat(repository.findProductionLine("L-SAME").orElseThrow().occupiedVersion())
                .isEqualTo(3);
    }

    @Test
    void allergenCarriedOverRequiresCertificateAndConsumesItAtomically() {
        long[] v1 = approvedAndFiled("P-SWITCH", 1);
        registerAllergens(v1[0], List.of(PEANUT, MILK));
        registerLine("L-SWITCH");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-SWITCH", v1[1], "B-1", 1, T0.toEpochMilli(), null));

        // 新版本去除花生声明；批准 v2 会暂停 v1 备案，再为 v2 备案（ACTIVE）。
        long[] v2 = approvedAndFiled("P-SWITCH", 2);
        registerAllergens(v2[0], List.of(MILK));

        long confirmedAt = T0.plusMillis(HOUR).toEpochMilli();
        long certId = confirmCertificate("L-SWITCH", confirmedAt, List.of(PEANUT));

        long occurredAt = confirmedAt + 30 * 60_000L;
        Results.ControlledProductionView production = clearanceService
                .declareControlledProduction(Fixtures.controlled("L-SWITCH", v2[1], "B-2", 2,
                        occurredAt, certId)).body();

        assertThat(production.certificateId()).isEqualTo(certId);
        assertThat(production.occupiedVersion()).isEqualTo(2);
        ProductionLineRow line = repository.findProductionLine("L-SWITCH").orElseThrow();
        assertThat(line.occupiedVersion()).isEqualTo(3);
        assertThat(line.lastFormulaId()).isEqualTo(v2[0]);
        ClearanceCertificateRow cert =
                repository.findClearanceCertificate(certId).orElseThrow();
        assertThat(cert.status()).isEqualTo("CONSUMED");
        assertThat(cert.controlledProductionId()).isEqualTo(production.controlledProductionId());
        assertThat(cert.consumedAt()).isNotNull();
    }

    @Test
    void noCarriedOverAllergenSwitchRejectsSuppliedCertificate() {
        long[] v1 = approvedAndFiled("P-NO-CARRY", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-NO-CARRY");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-NO-CARRY", v1[1], "B-1", 1, T0.toEpochMilli(), null));

        long[] v2 = approvedAndFiled("P-NO-CARRY", 2);
        // 新批声明集合为前批超集，无带入过敏原。
        registerAllergens(v2[0], List.of(PEANUT, EGG));
        long certId = confirmCertificate("L-NO-CARRY", T0.plusMillis(HOUR).toEpochMilli(),
                List.of(PEANUT));

        // 不传凭证：受理成功。
        clearanceService.declareControlledProduction(Fixtures.controlled("L-NO-CARRY", v2[1],
                "B-2", 2, T0.plusMillis(2 * HOUR).toEpochMilli(), null));
        assertThat(repository.findClearanceCertificate(certId).orElseThrow().status())
                .as("无需凭证的切换不得核销凭证").isEqualTo("VALID");
    }

    // ---------------- 失败分支 ----------------

    @Test
    void missingCertificateWhenAllergenCarriedOverIsRejectedAndConsumesNothing() {
        long[] v1 = approvedAndFiled("P-NEED-CERT", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-NEED-CERT");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-NEED-CERT", v1[1], "B-1", 1, T0.toEpochMilli(), null));
        long[] v2 = approvedAndFiled("P-NEED-CERT", 2);
        registerAllergens(v2[0], List.of(MILK));

        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-NEED-CERT", v2[1], "B-2", 2,
                        T0.plusMillis(HOUR).toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("CERTIFICATE_REQUIRED");
                });

        // 拒绝不写记录、不推进占用版本。
        ProductionLineRow line = repository.findProductionLine("L-NEED-CERT").orElseThrow();
        assertThat(line.occupiedVersion()).isEqualTo(2);
        assertThat(line.lastFormulaId()).isEqualTo(v1[0]);
        assertThat(clearanceService.listControlledProductions("L-NEED-CERT")).hasSize(1);
    }

    @Test
    void supplyingCertificateWhenNotRequiredIsRejected() {
        long[] v1 = approvedAndFiled("P-EXTRA-CERT", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-EXTRA-CERT");
        long certId = confirmCertificate("L-EXTRA-CERT", T0.toEpochMilli(), List.of(PEANUT));

        // 首批（无前批）却引用凭证：拒绝，凭证保持 VALID。
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-EXTRA-CERT", v1[1], "B-1", 1,
                        T0.plusMillis(HOUR).toEpochMilli(), certId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_NOT_REQUIRED"));
        assertThat(repository.findClearanceCertificate(certId).orElseThrow().status())
                .isEqualTo("VALID");
    }

    @Test
    void certificateExpiresExactlyAtTwoHoursButValidOneMillisecondBefore() {
        long[] v1 = approvedAndFiled("P-EXP", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-EXP");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-EXP", v1[1], "B-1", 1, T0.toEpochMilli(), null));
        long[] v2 = approvedAndFiled("P-EXP", 2);
        registerAllergens(v2[0], List.of(MILK));

        long confirmedAt = T0.plusMillis(HOUR).toEpochMilli();
        long validUntil = confirmedAt + 2 * HOUR;

        // 恰满 2 小时：失效。
        long expiredCert = confirmCertificate("L-EXP", confirmedAt, List.of(PEANUT));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-EXP", v2[1], "B-EXP", 2, validUntil, expiredCert)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_EXPIRED"));
        assertThat(repository.findClearanceCertificate(expiredCert).orElseThrow().status())
                .isEqualTo("VALID");

        // 恰满前 1 毫秒：仍有效，受理成功。
        long freshCert = confirmCertificate("L-EXP", confirmedAt, List.of(PEANUT));
        Results.ControlledProductionView accepted = clearanceService.declareControlledProduction(
                Fixtures.controlled("L-EXP", v2[1], "B-OK", 2, validUntil - 1, freshCert)).body();
        assertThat(accepted.certificateId()).isEqualTo(freshCert);
    }

    @Test
    void certificateScopeLineAndVersionAndPersonRulesAreEnforced() {
        long[] v1 = approvedAndFiled("P-RULES", 1);
        registerAllergens(v1[0], List.of(PEANUT, MILK));
        registerLine("L-RULES");
        registerLine("L-OTHER");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-RULES", v1[1], "B-1", 1, T0.toEpochMilli(), null));
        long[] v2 = approvedAndFiled("P-RULES", 2);
        registerAllergens(v2[0], List.of(MILK));
        long confirmedAt = T0.plusMillis(HOUR).toEpochMilli();
        long occurredAt = confirmedAt + 30 * 60_000L;

        // 操作人与复核人相同：422。
        assertThatThrownBy(() -> clearanceService.confirmClearance(Fixtures.clearance("L-RULES",
                "SAME", "SAME", confirmedAt - HOUR, confirmedAt, List.of(PEANUT))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CLEARANCE_TWO_PERSON_REQUIRED"));

        // 覆盖范围不足（只覆盖花生，缺乳？此处带入仅花生；改为缺覆盖：空集合已 400，使用错误过敏原 EGG）。
        long wrongScope = confirmCertificate("L-RULES", confirmedAt, List.of(EGG));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-RULES", v2[1], "B-2", 2, occurredAt, wrongScope)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_SCOPE_INSUFFICIENT"));

        // 线路不一致：凭证属于 L-OTHER（L-OTHER 占用版本仍为 1，与本线路无关）。
        long otherLine = confirmCertificate("L-OTHER", confirmedAt, List.of(PEANUT));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-RULES", v2[1], "B-3", 2, occurredAt, otherLine)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_LINE_MISMATCH"));

        // 全部拒绝均不写记录、不推进占用版本、不核销凭证。
        assertThat(repository.findProductionLine("L-RULES").orElseThrow().occupiedVersion())
                .isEqualTo(2);
        assertThat(clearanceService.listControlledProductions("L-RULES")).hasSize(1);
        assertThat(repository.findClearanceCertificate(wrongScope).orElseThrow().status())
                .isEqualTo("VALID");
        assertThat(repository.findClearanceCertificate(otherLine).orElseThrow().status())
                .isEqualTo("VALID");
    }

    @Test
    void certificateConfirmedAtOldOccupiedVersionRejectsSwitch() {
        // 独立构造：首批前（占用版本 1）确认凭证，首批后切换时占用版本为 2 → 版本不一致。
        long[] v1 = approvedAndFiled("P-OLDVER", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-OLDVER");
        long confirmedAt = T0.plusMillis(HOUR).toEpochMilli();
        long certAtVersion1 = confirmCertificate("L-OLDVER", confirmedAt, List.of(PEANUT));
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-OLDVER", v1[1], "B-1", 1, confirmedAt + 1, null));

        long[] v2 = approvedAndFiled("P-OLDVER", 2);
        registerAllergens(v2[0], List.of(MILK));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-OLDVER", v2[1], "B-2", 2,
                        confirmedAt + 30 * 60_000L, certAtVersion1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_LINE_VERSION_MISMATCH"));
        assertThat(repository.findClearanceCertificate(certAtVersion1).orElseThrow().status())
                .isEqualTo("VALID");
    }

    @Test
    void consumedCertificateCannotBeReused() {
        long[] v1 = approvedAndFiled("P-REUSE", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-REUSE");
        clearanceService.declareControlledProduction(
                Fixtures.controlled("L-REUSE", v1[1], "B-1", 1, T0.toEpochMilli(), null));
        long[] v2 = approvedAndFiled("P-REUSE", 2);
        registerAllergens(v2[0], List.of(MILK));
        long confirmedAt = T0.plusMillis(HOUR).toEpochMilli();
        long certId = confirmCertificate("L-REUSE", confirmedAt, List.of(PEANUT));
        clearanceService.declareControlledProduction(Fixtures.controlled("L-REUSE", v2[1], "B-2",
                2, confirmedAt + 1, certId));

        // 第三版不再声明乳，切换需要新凭证；复用已核销凭证 → CERTIFICATE_NOT_VALID。
        long[] v3 = approvedAndFiled("P-REUSE", 3);
        registerAllergens(v3[0], List.of());
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-REUSE", v3[1], "B-3", 3,
                        confirmedAt + 2, certId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("CERTIFICATE_NOT_VALID"));
        assertThat(repository.findProductionLine("L-REUSE").orElseThrow().occupiedVersion())
                .isEqualTo(3);
        assertThat(clearanceService.listControlledProductions("L-REUSE")).hasSize(2);
    }

    @Test
    void cancelledAndSuspendedFilingsAreRejectedByControlledGate() {
        long[] v1 = approvedAndFiled("P-FGATE", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-FGATE");

        complianceService.cancelFiling(Fixtures.filingRef(v1[1]));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-FGATE", v1[1], "B-X", 1, T0.toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("FILING_NOT_EFFECTIVE");
                });
        assertThat(clearanceService.listControlledProductions("L-FGATE")).isEmpty();
    }

    @Test
    void wrongOccupiedVersionIsRejected() {
        long[] v1 = approvedAndFiled("P-OCC", 1);
        registerAllergens(v1[0], List.of());
        registerLine("L-OCC");
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-OCC", v1[1], "B-1", 99, T0.toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("OCCUPIED_VERSION_MISMATCH"));
        assertThat(repository.findProductionLine("L-OCC").orElseThrow().occupiedVersion())
                .isEqualTo(1);
    }

    // ---------------- 登记版本 / 400 / 404 ----------------

    @Test
    void allergenRegistrationStartsAtOneAndOptimisticVersionBumps() {
        long[] v1 = approvedAndFiled("P-REG", 1);
        Results.FormulaAllergenView first = clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], null, List.of(PEANUT))).body();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.allergens()).containsExactly(PEANUT);

        Results.FormulaAllergenView second = clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], 1, List.of(PEANUT, MILK))).body();
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.allergens()).containsExactly(MILK, PEANUT);

        // 过期 expectedVersion：409，集合不变。
        assertThatThrownBy(() -> clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], 1, List.of(EGG))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ALLERGEN_VERSION_CONFLICT"));
        assertThat(clearanceService.getFormulaAllergens(v1[0]).allergens())
                .containsExactly(MILK, PEANUT);
    }

    @Test
    void duplicateRegistrationWithoutExpectedVersionConflicts() {
        long[] v1 = approvedAndFiled("P-DUPREG", 1);
        clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], null, List.of(PEANUT)));
        assertThatThrownBy(() -> clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], null, List.of(MILK))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ALLERGEN_REG_EXISTS"));
    }

    @Test
    void invalidParamsReturn400AndMissingObjectsReturn404() {
        long[] v1 = approvedAndFiled("P-CODE", 1);
        registerLine("L-CODE");

        // 未知过敏原编码：400。
        assertThatThrownBy(() -> clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(v1[0], null, List.of("AL_NOT_EXIST"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 空批次号 / 负数量 / 缺时刻：400。
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-CODE", v1[1], " ", 1, T0.toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled(Fixtures.requestId(), "L-CODE", v1[1], "B", "-1", 1,
                        T0.toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                new com.example.starter.compliance.web.Requests.ControlledProductionRequest(
                        Fixtures.requestId(), "L-CODE", v1[1], "B",
                        new java.math.BigDecimal("1"), 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 对象不存在：404。
        assertThatThrownBy(() -> clearanceService.getLine("L-MISSING"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> clearanceService.getFormulaAllergens(987_654_321L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(
                Fixtures.controlled("L-CODE", 987_654_321L, "B-404", 1,
                        T0.toEpochMilli(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------- 幂等 ----------------

    @Test
    void controlledProductionIdempotentReplayKeepsSingleRecord() {
        long[] v1 = approvedAndFiled("P-CIDEM", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-CIDEM");
        var request = Fixtures.controlled(Fixtures.requestId(), "L-CIDEM", v1[1], "B-1", "10",
                1, T0.toEpochMilli(), null);

        ServiceResult<Results.ControlledProductionView> first =
                clearanceService.declareControlledProduction(request);
        ServiceResult<Results.ControlledProductionView> replay =
                clearanceService.declareControlledProduction(request);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().controlledProductionId())
                .isEqualTo(first.body().controlledProductionId());
        assertThat(clearanceService.listControlledProductions("L-CIDEM")).hasSize(1);
        assertThat(repository.findProductionLine("L-CIDEM").orElseThrow().occupiedVersion())
                .isEqualTo(2);
    }

    @Test
    void sameKeyDifferentParamsConflictAndFailedRequestDoesNotOccupyKey() {
        long[] v1 = approvedAndFiled("P-CKEY", 1);
        registerAllergens(v1[0], List.of(PEANUT));
        registerLine("L-CKEY");
        String requestId = Fixtures.requestId();
        var first = Fixtures.controlled(requestId, "L-CKEY", v1[1], "B-1", "10", 1,
                T0.toEpochMilli(), null);
        clearanceService.declareControlledProduction(first);

        // 异参（不同精确时刻）：409，不产生第二条记录。
        var different = Fixtures.controlled(requestId, "L-CKEY", v1[1], "B-1", "10", 1,
                T0.plusMillis(1).toEpochMilli(), null);
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REQUEST_PARAM_CONFLICT"));

        // 失败不占键：一个新 requestId 先以错误占用版本失败，再以同键修正参数应成功。
        String retryKey = Fixtures.requestId();
        var failed = Fixtures.controlled(retryKey, "L-CKEY", v1[1], "B-2", "10", 99,
                T0.toEpochMilli(), null);
        assertThatThrownBy(() -> clearanceService.declareControlledProduction(failed))
                .isInstanceOf(ApiException.class);
        var corrected = Fixtures.controlled(retryKey, "L-CKEY", v1[1], "B-2", "10", 2,
                T0.toEpochMilli(), null);
        Results.ControlledProductionView retried =
                clearanceService.declareControlledProduction(corrected).body();
        assertThat(retried).isNotNull();
        assertThat(clearanceService.listControlledProductions("L-CKEY")).hasSize(2);
    }
}
