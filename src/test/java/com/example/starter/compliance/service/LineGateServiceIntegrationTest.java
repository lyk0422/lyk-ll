package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.CleaningCertificateRow;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.ProductionLineRow;
import com.example.starter.compliance.web.Requests.CleaningCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 过敏原清线凭证门禁服务层 H2 测试：
 * 主流程切换、凭证必需性与覆盖范围、2 小时边界、双人确认、核销与作废、失败不消耗凭证、
 * expectedVersion 版本、备案状态、查询视图。
 */
class LineGateServiceIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final long H2 = LineGateService.CERTIFICATE_TTL_MILLIS;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private LineGateService gateService;

    // ---------------- 过敏原登记 ----------------

    @Test
    void allergenRegistrationStartsAtVersionOneAndUpdatesOptimistically() {
        setInstant(T0);
        long formulaId = approvedFormula("P-REG", 1);

        Results.FormulaAllergenView first = gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 0, List.of("MILK", "EGG"))).body();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.allergenCodes()).containsExactly("EGG", "MILK");

        Results.FormulaAllergenView second = gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 1, List.of("MILK"))).body();
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.allergenCodes()).containsExactly("MILK");

        assertThat(gateService.getFormulaAllergens(formulaId).version()).isEqualTo(2);
    }

    @Test
    void allergenRegistrationRejectsBadVersionsAndUnknownCodes() {
        long formulaId = approvedFormula("P-REG2", 1);

        // 首次登记 expectedVersion 非 0：409。
        assertThatThrownBy(() -> gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 1, List.of("MILK"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT"));

        gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 0, List.of("MILK")));
        // 过期版本号：409。
        assertThatThrownBy(() -> gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 0, List.of("EGG"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT"));

        // 未知过敏原：400；重复编码：400。
        assertThatThrownBy(() -> gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 1, List.of("GLUTEN"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.BAD_REQUEST, "ALLERGEN_NOT_FOUND"));
        assertThatThrownBy(() -> gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 1, List.of("EGG", "EGG"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.BAD_REQUEST, "INVALID_PARAM"));
        // 失败不改动版本。
        assertThat(gateService.getFormulaAllergens(formulaId).version()).isEqualTo(1);
    }

    @Test
    void allergenRegistrationOnMissingFormulaReturns404() {
        assertThatThrownBy(() -> gateService.registerFormulaAllergens(
                Fixtures.allergens(999_999L, 0, List.of("MILK"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.NOT_FOUND, "FORMULA_NOT_FOUND"));
    }

    // ---------------- 生产线 ----------------

    @Test
    void lineStartsAtOccupancyVersionOneWithNoLastFormula() {
        setInstant(T0);
        Results.ProductionLineView view =
                gateService.registerLine(Fixtures.line("L-INIT")).body();
        assertThat(view.occupancyVersion()).isEqualTo(1);
        assertThat(view.lastFormulaId()).isNull();

        assertThatThrownBy(() -> gateService.registerLine(Fixtures.line("L-INIT")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "LINE_EXISTS"));
    }

    // ---------------- 受控量产主流程与门禁 ----------------

    @Test
    void firstProductionNeedsNoCertificateAndAdvancesLine() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");

        Results.ControlledProductionView result = gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 1, milkEgg.filingId(), null, "B-1", "100",
                        T0.toEpochMilli())).body();
        assertThat(result.occupancyVersion()).isEqualTo(1);
        assertThat(result.certificateId()).isNull();

        ProductionLineRow line = findLine("L1");
        assertThat(line.occupancyVersion()).isEqualTo(2);
        assertThat(line.lastFormulaId()).isEqualTo(milkEgg.formulaId());
        assertThat(productions("L1")).hasSize(1);
    }

    @Test
    void noCertificateSwitchRejectsAnySuppliedCertificate() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        gateService.acceptControlledProduction(Fixtures.controlled("L1", 1, milkEgg.filingId(),
                null, "B-1", "100", T0.toEpochMilli()));

        // 新批过敏原是前批超集（仅新增 SOY）：不需要凭证，携带凭证也拒绝，受控入口不能绕过门禁语义。
        Product more = product("P-D-MILK-EGG-SOY", 1, "MILK", "EGG", "SOY");
        registerAllergens(more.formulaId(), "MILK", "EGG", "SOY");
        long certId = confirmCertificate("L1", "op1", "rv1", T0.toEpochMilli(), "SOY");
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, more.filingId(), certId, "B-2", "100",
                        T0.toEpochMilli() + 60_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_NOT_REQUIRED"));
    }

    @Test
    void switchWithRemovedAllergenRequiresCoveringCertificateAndConsumesIt() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        produceFirst("L1", milkEgg, 1);

        // 缺凭证：422 CERTIFICATE_REQUIRED。
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, milkOnly.filingId(), null, "B-2", "100",
                        T0.toEpochMilli() + 60_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_REQUIRED"));

        // 覆盖不足（只清 MILK，缺 EGG）：422，凭证不被消耗。
        long wrongCert = confirmCertificate("L1", "op1", "rv1", T0.toEpochMilli() + 1_000, "MILK");
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, milkOnly.filingId(), wrongCert, "B-2", "100",
                        T0.toEpochMilli() + 60_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_COVERAGE_INSUFFICIENT"));
        CleaningCertificateRow wrongRow = certificate(wrongCert);
        assertThat(wrongRow.status()).isEqualTo("VALID");
        assertThat(wrongRow.consumedAt()).isNull();

        // 覆盖 EGG 的凭证：受理成功，凭证核销，占用版本推进。
        long certId = confirmCertificate("L1", "op1", "rv1", T0.toEpochMilli() + 2_000, "EGG");
        long extraId = confirmCertificate("L1", "op2", "rv2", T0.toEpochMilli() + 3_000,
                "EGG", "MILK");
        Results.ControlledProductionView result = gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, milkOnly.filingId(), certId, "B-2", "100",
                        T0.toEpochMilli() + 60_000)).body();
        assertThat(result.certificateId()).isEqualTo(certId);
        assertThat(result.occupancyVersion()).isEqualTo(2);

        assertThat(certificate(certId).status()).isEqualTo("CONSUMED");
        // 后续生产先提交：同线其余 VALID 凭证即使在 2 小时窗口内也立即作废。
        assertThat(certificate(extraId).status()).isEqualTo("SUPERSEDED");
        ProductionLineRow line = findLine("L1");
        assertThat(line.occupancyVersion()).isEqualTo(3);
        assertThat(line.lastFormulaId()).isEqualTo(milkOnly.formulaId());

        // 已核销凭证不能再次使用：422。
        Product milkEggAgain = product("P-A2-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEggAgain.formulaId(), "MILK", "EGG");
        gateService.acceptControlledProduction(Fixtures.controlled("L1", 3,
                milkEggAgain.filingId(), null, "B-3", "100", T0.toEpochMilli() + 120_000));
        Product milkOnly2 = product("P-B2-MILK", 1, "MILK");
        registerAllergens(milkOnly2.formulaId(), "MILK");
        long newCert = confirmCertificate("L1", "op3", "rv3", T0.toEpochMilli() + 120_000, "EGG");
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 4, milkOnly2.filingId(), certId, "B-4", "100",
                        T0.toEpochMilli() + 180_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_NOT_VALID"));
        // 新凭证核销成功。
        assertThat(gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 4, milkOnly2.filingId(), newCert, "B-4", "100",
                        T0.toEpochMilli() + 180_000)).body().certificateId()).isEqualTo(newCert);
    }

    @Test
    void certificateTwoHourWindowIsHalfOpenExactly() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        produceFirst("L1", milkEgg, 1);

        long cleanedAt = T0.toEpochMilli() + 10_000;
        long certId = confirmCertificate("L1", "op1", "rv1", cleanedAt, "EGG");

        // 生产时刻早于清线时刻：拒绝。
        assertThatThrownBy(() -> produceWith("L1", 2, milkOnly.filingId(), certId,
                cleanedAt - 1, "B-EARLY"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "PRODUCTION_BEFORE_CLEANING"));
        // 恰满 2 小时：失效。
        assertThatThrownBy(() -> produceWith("L1", 2, milkOnly.filingId(), certId,
                cleanedAt + H2, "B-EXACT"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_EXPIRED"));
        // 差 1 毫秒满 2 小时：受理。
        Results.ControlledProductionView ok = produceWith("L1", 2, milkOnly.filingId(), certId,
                cleanedAt + H2 - 1, "B-OK");
        assertThat(ok.certificateId()).isEqualTo(certId);
    }

    @Test
    void certificateChecksOperatorReviewerLineAndVersion() {
        setupClockAndFormulas();
        registerLine("L1");
        registerLine("L2");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        produceFirst("L1", milkEgg, 1);

        // 同一人操作与复核：422。
        CleaningCertificateRequest samePerson = Fixtures.cleaning("L1", "same", "same",
                T0.toEpochMilli(), List.of("EGG"));
        assertThatThrownBy(() -> gateService.confirmCleaning(samePerson))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CLEANING_SAME_PERSON"));

        // 凭证属于其他线路：422。
        long otherLineCert = confirmCertificate("L2", "op1", "rv1",
                T0.toEpochMilli(), "EGG");
        assertThatThrownBy(() -> produceWith("L1", 2, milkOnly.filingId(), otherLineCert,
                T0.toEpochMilli() + 60_000, "B-LINE"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_LINE_MISMATCH"));

        // 凭证固定的线路版本过期：首产后在占用版本 2 上确认凭证，随后一笔无需凭证的量产先提交，
        // 线路推进到 3 且旧凭证立即作废（SUPERSEDED）；再用旧凭证量产被拒，线路停在版本 3。
        long versionTwoCert = confirmCertificate("L1", "op1", "rv1",
                T0.toEpochMilli() + 10_000, "EGG", "MILK");
        Product more = product("P-D-MILK-EGG-SOY", 1, "MILK", "EGG", "SOY");
        registerAllergens(more.formulaId(), "MILK", "EGG", "SOY");
        // 新批为前批超集（仅新增 SOY），无需凭证即可把线路从版本 2 推进到 3。
        gateService.acceptControlledProduction(Fixtures.controlled("L1", 2, more.filingId(),
                null, "B-FWD", "100", T0.toEpochMilli() + 30_000));
        assertThat(certificate(versionTwoCert).status()).isEqualTo("SUPERSEDED");
        Product soyOnly = product("P-E-SOY", 1, "SOY");
        registerAllergens(soyOnly.formulaId(), "SOY");
        assertThatThrownBy(() -> produceWith("L1", 3, soyOnly.filingId(), versionTwoCert,
                T0.toEpochMilli() + 40_000, "B-STALE"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY,
                        "CERTIFICATE_NOT_VALID"));
        assertThat(findLine("L1").occupancyVersion()).isEqualTo(3);
        assertThat(productions("L1")).hasSize(2);
    }

    @Test
    void controlledProductionRejectsInvalidParamsMissingObjectsAndBadFilingStatus() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        produceFirst("L1", milkEgg, 1);

        // expectedVersion 非法：400。
        ControlledProductionRequest badVersion = Fixtures.controlled("L1", 0, milkEgg.filingId(),
                null, "B", "1", T0.toEpochMilli());
        assertThatThrownBy(() -> gateService.acceptControlledProduction(badVersion))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.BAD_REQUEST, "INVALID_PARAM"));
        // 线路不存在：404；备案不存在：404；凭证不存在：404。
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("NOPE", 1, milkEgg.filingId(), null, "B", "1",
                        T0.toEpochMilli())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.NOT_FOUND, "LINE_NOT_FOUND"));
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, 999_999L, null, "B", "1", T0.toEpochMilli())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.NOT_FOUND, "FILING_NOT_FOUND"));
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        assertThatThrownBy(() -> produceWith("L1", 2, milkOnly.filingId(), 999_999L,
                T0.toEpochMilli(), "B"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND"));
        // 占用版本不匹配：409。
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 9, milkOnly.filingId(), null, "B", "1",
                        T0.toEpochMilli())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT"));

        // 备案注销/暂停：422，且线路不推进、无生产记录。
        complianceService.cancelFiling(Fixtures.filingRef(milkOnly.filingId()));
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("L1", 2, milkOnly.filingId(), null, "B", "1",
                        T0.toEpochMilli())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY, "FILING_CANCELLED"));
        assertThat(findLine("L1").occupancyVersion()).isEqualTo(2);
        assertThat(productions("L1")).hasSize(1);
    }

    @Test
    void failureLeavesCertificateValidAndLineUntouched() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        produceFirst("L1", milkEgg, 1);
        long certId = confirmCertificate("L1", "op1", "rv1", T0.toEpochMilli(), "EGG");

        // 注销备案导致受理失败：凭证必须保持 VALID，线路不推进。
        complianceService.cancelFiling(Fixtures.filingRef(milkOnly.filingId()));
        assertThatThrownBy(() -> produceWith("L1", 2, milkOnly.filingId(), certId,
                T0.toEpochMilli() + 60_000, "B-FAIL"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY, "FILING_CANCELLED"));
        CleaningCertificateRow row = certificate(certId);
        assertThat(row.status()).isEqualTo("VALID");
        assertThat(row.consumedAt()).isNull();
        assertThat(findLine("L1").occupancyVersion()).isEqualTo(2);
        assertThat(productions("L1")).hasSize(1);
    }

    @Test
    void overdueReviewWatchFilingSuspendsIndependentlyAndRejectsProduction() {
        setupClockAndFormulas();
        registerLine("LOW");
        // 使用直接添加剂 E202 以便标准收紧触发复审观察。
        long formulaId = complianceService.submitFormula(Fixtures.formula("P-OW", 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        long filingId = complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
        gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 0, List.of("MILK")));
        gateService.acceptControlledProduction(Fixtures.controlled("LOW", 1, filingId,
                null, "B0", "100", T0.toEpochMilli()));

        // 标准收紧：备案进入 REVIEW_WATCH。
        complianceService.updateStandard(Fixtures.standard("E202", "BEV01", "0.1"));
        // 推进到第 91 个 UTC 日：逾期，受控量产触发独立事务暂停并拒绝，线路不推进、无新记录。
        setInstant(T0.plusSeconds(91L * 24 * 3600));
        assertThatThrownBy(() -> gateService.acceptControlledProduction(
                Fixtures.controlled("LOW", 2, filingId, null, "B-LATE", "100",
                        clock.nowMillis())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "FILING_OVERDUE"));
        assertThat(repository.findFilingById(filingId).orElseThrow().status())
                .isEqualTo("SUSPENDED");
        assertThat(findLine("LOW").occupancyVersion()).isEqualTo(2);
        assertThat(productions("LOW")).hasSize(1);
    }

    // ---------------- 幂等 ----------------

    @Test
    void controlledProductionIdempotencyReplaysAndFreesKeyOnFailure() {
        setupClockAndFormulas();
        registerLine("L1");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");

        String key = Fixtures.requestId();
        ControlledProductionRequest request = Fixtures.controlled(key, "L1", 1,
                milkEgg.filingId(), null, "B-IDEM", "100", T0.toEpochMilli());
        ServiceResult<Results.ControlledProductionView> first =
                gateService.acceptControlledProduction(request);
        ServiceResult<Results.ControlledProductionView> replay =
                gateService.acceptControlledProduction(request);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().recordId()).isEqualTo(first.body().recordId());
        assertThat(productions("L1")).hasSize(1);

        // 同键异参（精确时刻不同）：409。
        ControlledProductionRequest differentMoment = Fixtures.controlled(key, "L1", 2,
                milkEgg.filingId(), null, "B-IDEM", "100", T0.toEpochMilli() + 1);
        assertThatThrownBy(() -> gateService.acceptControlledProduction(differentMoment))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "REQUEST_PARAM_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        setupClockAndFormulas();
        registerLine("L9");
        Product milkEgg = product("P-A-MILK-EGG", 1, "MILK", "EGG");
        registerAllergens(milkEgg.formulaId(), "MILK", "EGG");
        Product milkOnly = product("P-B-MILK", 1, "MILK");
        registerAllergens(milkOnly.formulaId(), "MILK");
        produceFirstWithLine("L9", milkEgg, 1);

        String key = Fixtures.requestId();
        // 同键首次：缺凭证 422（事务回滚，不占键）。
        ControlledProductionRequest missingCert = Fixtures.controlled(key, "L9", 2,
                milkOnly.filingId(), null, "B-X", "100", T0.toEpochMilli() + 60_000);
        assertThatThrownBy(() -> gateService.acceptControlledProduction(missingCert))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_REQUIRED"));
        // 同键但参数改为合法凭证：作为首次请求成功（若占位行未回滚会 409）。
        long certId = confirmCertificateOn("L9", "op1", "rv1", T0.toEpochMilli(), "EGG");
        ControlledProductionRequest withCert = Fixtures.controlled(key, "L9", 2,
                milkOnly.filingId(), certId, "B-X", "100", T0.toEpochMilli() + 60_000);
        ServiceResult<Results.ControlledProductionView> accepted =
                gateService.acceptControlledProduction(withCert);
        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.body().certificateId()).isEqualTo(certId);
        // 再次同参：重放首次冻结结果。
        assertThat(gateService.acceptControlledProduction(withCert).replayed()).isTrue();
    }

    @Test
    void certificateConfirmationIsIdempotentWithFrozenResult() {
        setupClockAndFormulas();
        registerLine("L1");
        String key = Fixtures.requestId();
        CleaningCertificateRequest request = Fixtures.cleaning(key, "L1", "op1", "rv1",
                T0.toEpochMilli(), List.of("EGG", "MILK"));
        Results.CleaningCertificateView first = gateService.confirmCleaning(request).body();
        Results.CleaningCertificateView replay = gateService.confirmCleaning(request).body();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.certificateId()).isEqualTo(first.certificateId());
        assertThat(certificates("L1")).hasSize(1);

        CleaningCertificateRequest different = Fixtures.cleaning(key, "L1", "op1", "rv1",
                T0.toEpochMilli() + 1, List.of("EGG"));
        assertThatThrownBy(() -> gateService.confirmCleaning(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApi(ex, HttpStatus.CONFLICT, "REQUEST_PARAM_CONFLICT"));
    }

    // ---------------- 辅助 ----------------

    private void setupClockAndFormulas() {
        setInstant(T0);
    }

    private void registerLine(String lineCode) {
        gateService.registerLine(Fixtures.line(lineCode));
    }

    private long approvedFormula(String productCode, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E300", "0.1")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        return formulaId;
    }

    private Product product(String productCode, int version, String... allergens) {
        long formulaId = approvedFormula(productCode, version);
        long filingId = complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
        return new Product(formulaId, filingId, List.of(allergens));
    }

    private void registerAllergens(long formulaId, String... codes) {
        gateService.registerFormulaAllergens(
                Fixtures.allergens(formulaId, 0, List.of(codes)));
    }

    private long confirmCertificate(String lineCode, String operator, String reviewer,
            long cleanedAt, String... allergens) {
        return confirmCertificateOn(lineCode, operator, reviewer, cleanedAt, allergens);
    }

    private long confirmCertificateOn(String lineCode, String operator, String reviewer,
            long cleanedAt, String... allergens) {
        return gateService.confirmCleaning(Fixtures.cleaning(lineCode, operator, reviewer,
                cleanedAt, List.of(allergens))).body().certificateId();
    }

    private void produceFirst(String lineCode, Product product, int expectedVersion) {
        produceFirstWithLine(lineCode, product, expectedVersion);
    }

    private void produceFirstWithLine(String lineCode, Product product, int expectedVersion) {
        gateService.acceptControlledProduction(Fixtures.controlled(lineCode, expectedVersion,
                product.filingId(), null, "B-FIRST", "100", T0.toEpochMilli()));
    }

    private Results.ControlledProductionView produceWith(String lineCode, int expectedVersion,
            long filingId, Long certificateId, long occurredAt, String batchNo) {
        return gateService.acceptControlledProduction(Fixtures.controlled(lineCode,
                expectedVersion, filingId, certificateId, batchNo, "100", occurredAt)).body();
    }

    private ProductionLineRow findLine(String lineCode) {
        return gateRepository.findProductionLine(lineCode).orElseThrow();
    }

    private CleaningCertificateRow certificate(long certificateId) {
        return gateRepository.findCertificateById(certificateId).orElseThrow();
    }

    private List<Results.CleaningCertificateView> certificates(String lineCode) {
        return gateService.getLineCertificates(lineCode);
    }

    private List<Results.ControlledProductionView> productions(String lineCode) {
        return gateService.getLineProductions(lineCode);
    }

    private static void assertApi(Throwable ex, HttpStatus status, String code) {
        ApiException api = (ApiException) ex;
        assertThat(api.getStatus()).isEqualTo(status);
        assertThat(api.getCode()).isEqualTo(code);
    }

    private record Product(long formulaId, long filingId, List<String> allergens) {
    }
}
