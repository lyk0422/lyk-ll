package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.RegisterReworkRequest;
import com.example.starter.compliance.web.Requests.ReworkUseDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 返工料登记与受控量产投用 H2 测试：
 * 登记冻结来源配方版本与过敏原集合、唯一料号/唯一来源、料账乐观版本、登记量修正边界；
 * 量产投用的 20% 精确边界、过敏原并集声明门禁、多料回滚零扣、备案失效零扣、
 * 幂等重放/异参 409/失败不占键；未投返工料的旧量产行为保留。
 */
class ReworkIntegrationTest extends AbstractIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String MILK = "ALG_MILK";
    private static final String EGG = "ALG_EGG";
    private static final String PEANUT = "ALG_PEANUT";

    @Autowired
    private LineControlService lineService;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private ReworkService reworkService;

    private long approvedFiling(String productCode, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E300", "0.2")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        return complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }

    private void registerAllergens(String productCode, int version, List<String> allergens) {
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), productCode,
                version, allergens, 0, T0));
    }

    private void freshLine(String lineCode) {
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), lineCode, lineCode + "-线",
                null, null, T0));
    }

    /** 造一条已成功受控量产记录作为返工料来源，返回量产记录 id。 */
    private long sourceRecord(String productCode, List<String> allergens, String lineCode,
            String quantityKg) {
        long filingId = approvedFiling(productCode, 1);
        registerAllergens(productCode, 1, allergens);
        freshLine(lineCode);
        return lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                filingId, lineCode, 1, "B-SRC-" + productCode, quantityKg, null, T0 + 1000))
                .body().productionRecordId();
    }

    private ReworkResults.ReworkMaterialView register(String materialCode, long recordId,
            String availableKg) {
        return reworkService.registerRework(Fixtures.rework(Fixtures.requestId(), materialCode,
                recordId, availableKg, 0, T0 + 2000)).body();
    }

    @Test
    void registrationFreezesSourceAndReplaysIdempotently() {
        long recordId = sourceRecord("P-SRC", List.of(MILK), "L-SRC", "50");
        String requestId = Fixtures.requestId();
        RegisterReworkRequest request = Fixtures.rework(requestId, "RW-1", recordId, "30.500",
                0, T0 + 2000);
        ServiceResult<ReworkResults.ReworkMaterialView> created =
                reworkService.registerRework(request);
        ReworkResults.ReworkMaterialView view = created.body();
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.materialCode()).isEqualTo("RW-1");
        assertThat(view.sourceProductionRecordId()).isEqualTo(recordId);
        assertThat(view.sourceProductCode()).isEqualTo("P-SRC");
        assertThat(view.sourceFormulaVersion()).isEqualTo(1);
        assertThat(view.allergens()).containsExactly(MILK);
        assertThat(view.availableKg()).isEqualTo("30.5");
        assertThat(view.consumedKg()).isEqualTo("0");
        assertThat(view.balanceKg()).isEqualTo("30.5");

        // 同键同参重放首次冻结结果，不重复登记、版本不加。
        ServiceResult<ReworkResults.ReworkMaterialView> replay =
                reworkService.registerRework(request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().version()).isEqualTo(1);
        assertThat(replay.body().materialId()).isEqualTo(view.materialId());

        // 同键异参（改登记量）：409。
        RegisterReworkRequest different = Fixtures.rework(requestId, "RW-1", recordId, "31",
                0, T0 + 2000);
        assertThatThrownBy(() -> reworkService.registerRework(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("REQUEST_PARAM_CONFLICT");
                });
    }

    @Test
    void registrationRejectsUnknownSourceDuplicateAndBadAmount() {
        long recordId = sourceRecord("P-SRC2", List.of(MILK), "L-SRC2", "50");

        // 不存在的来源量产记录（含外部/他人配方记录）：404。
        assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                Fixtures.requestId(), "RW-GHOST", 999_999L, "10", 0, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("REWORK_SOURCE_NOT_FOUND");
                });

        register("RW-2", recordId, "30");
        // 唯一料号：同码再登记 409。
        assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                Fixtures.requestId(), "RW-2", recordId, "5", 0, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_EXISTS"));
        // 同来源只登记一次：换料号引用同一记录 409。
        long record2 = sourceRecord("P-SRC3", List.of(EGG), "L-SRC3", "20");
        register("RW-3", record2, "10");
        assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                Fixtures.requestId(), "RW-3B", record2, "5", 0, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_SOURCE_ALREADY_REGISTERED"));

        // 登记量非正 / 超过三位小数：400。
        for (String bad : List.of("0", "-1", "1.0005")) {
            assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                    Fixtures.requestId(), "RW-BAD-" + bad, recordId, bad, 0, T0)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
        // 新增缺来源记录：400。
        assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                Fixtures.requestId(), "RW-NOSRC", null, "10", 0, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void registrationRequiresSourceAllergenSetAndQuotaIsIndependent() {
        // 旧未指定生产线入口的成功量产记录也可作来源；来源版本未登记过敏原则拒绝。
        long filingNoAlg = approvedFiling("P-NOALG", 1);
        complianceService.reportProduction(Fixtures.production(filingNoAlg, "B-LEG", "10"));
        long legacyRecordId = repository.findProductionRecordsByFiling(filingNoAlg).get(0).id();
        assertThatThrownBy(() -> reworkService.registerRework(Fixtures.rework(
                Fixtures.requestId(), "RW-NOALG", legacyRecordId, "5", 0, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_SOURCE_ALLERGEN_NOT_REGISTERED");
                });

        // 登记量为独立合成额度：来源记录仅产 10 公斤，登记 80 公斤合法（不声称旧记录已有产量）。
        registerAllergens("P-NOALG", 1, List.of(MILK));
        ReworkResults.ReworkMaterialView view = register("RW-LEG", legacyRecordId, "80");
        assertThat(view.availableKg()).isEqualTo("80");
        assertThat(view.allergens()).containsExactly(MILK);
    }

    @Test
    void updateAdjustsAmountWithOptimisticVersionButKeepsSourceAndAllergens() {
        long recordId = sourceRecord("P-UPD", List.of(MILK), "L-UPD", "50");
        register("RW-U", recordId, "30");

        // 修正登记量：expectedVersion=1 → 成功版本加 1。
        ReworkResults.ReworkMaterialView updated = reworkService.registerRework(
                Fixtures.rework(Fixtures.requestId(), "RW-U", null, "40", 1, T0 + 3000)).body();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.availableKg()).isEqualTo("40");
        assertThat(updated.allergens()).containsExactly(MILK);

        // 旧版 409，版本保持。
        assertThatThrownBy(() -> reworkService.registerRework(
                Fixtures.rework(Fixtures.requestId(), "RW-U", null, "45", 1, T0 + 4000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("OPTIMISTIC_VERSION_CONFLICT"));
        assertThat(reworkService.getMaterial("RW-U").version()).isEqualTo(2);

        // 料号不得换来源：422。
        long otherRecord = sourceRecord("P-UPD2", List.of(EGG), "L-UPD2", "10");
        assertThatThrownBy(() -> reworkService.registerRework(
                Fixtures.rework(Fixtures.requestId(), "RW-U", otherRecord, "40", 2, T0 + 5000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_SOURCE_IMMUTABLE");
                });

        // 修改不存在的料号：404。
        assertThatThrownBy(() -> reworkService.registerRework(
                Fixtures.rework(Fixtures.requestId(), "RW-UNKNOWN", null, "10", 1, T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        // 消费后登记量不得修正到低于已消费量。
        long targetFiling = approvedFiling("P-UPDT", 1);
        registerAllergens("P-UPDT", 1, List.of(MILK));
        freshLine("L-UPDT");
        lineService.controlledProduction(Fixtures.controlledWithRework(Fixtures.requestId(),
                targetFiling, "L-UPDT", 1, "B-U1", "100", null, T0 + 6000,
                List.of(Fixtures.reworkUse("RW-U", "20"))));
        assertThat(reworkService.getMaterial("RW-U").consumedKg()).isEqualTo("20");
        assertThatThrownBy(() -> reworkService.registerRework(
                Fixtures.rework(Fixtures.requestId(), "RW-U", null, "10", 3, T0 + 7000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_AMOUNT_BELOW_CONSUMED"));
    }

    @Test
    void productionWithReworkAtExactTwentyPercentSucceedsAndReconciles() {
        long recordId = sourceRecord("P-MSRC", List.of(MILK), "L-MSRC", "50");
        register("RW-M", recordId, "30");
        long targetFiling = approvedFiling("P-MDST", 1);
        registerAllergens("P-MDST", 1, List.of(MILK, EGG));
        freshLine("L-MDST");

        // 100 公斤量产投 20 公斤返工料：恰等 20% 上限，允许。
        String requestId = Fixtures.requestId();
        ControlledProductionRequest request = Fixtures.controlledWithRework(requestId,
                targetFiling, "L-MDST", 1, "B-M1", "100", null, T0 + 8000,
                List.of(Fixtures.reworkUse("RW-M", "20")));
        ServiceResult<LineControlResults.ControlledProductionView> accepted =
                lineService.controlledProduction(request);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().reworks()).hasSize(1);
        assertThat(accepted.body().reworks().get(0).materialCode()).isEqualTo("RW-M");
        assertThat(accepted.body().reworks().get(0).weightKg()).isEqualTo("20");

        // 料账勾稽：原量 30、消费 20、余额 10，料账版本推进。
        ReworkResults.ReworkMaterialView material = reworkService.getMaterial("RW-M");
        assertThat(material.availableKg()).isEqualTo("30");
        assertThat(material.consumedKg()).isEqualTo("20");
        assertThat(material.balanceKg()).isEqualTo("10");
        assertThat(material.version()).isEqualTo(2);

        // 消费流水勾稽到量产记录；线路量产历史带投用明细。
        ReworkResults.ReworkConsumptionListView consumptions =
                reworkService.getConsumptions("RW-M");
        assertThat(consumptions.consumptions()).hasSize(1);
        assertThat(consumptions.consumptions().get(0).productionRecordId())
                .isEqualTo(accepted.body().productionRecordId());
        assertThat(consumptions.consumptions().get(0).weightKg()).isEqualTo("20");
        LineControlResults.LineProductionView history =
                lineService.getLineProductions("L-MDST");
        assertThat(history.productions()).hasSize(1);
        assertThat(history.productions().get(0).reworks()).hasSize(1);

        // 同键同参重放：返回首次结果，不二次扣减。
        ServiceResult<LineControlResults.ControlledProductionView> replay =
                lineService.controlledProduction(request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().productionRecordId())
                .isEqualTo(accepted.body().productionRecordId());
        assertThat(reworkService.getMaterial("RW-M").consumedKg()).isEqualTo("20");
    }

    @Test
    void undeclaredSourceAllergenIsRejectedEvenWithCertificateAndZeroDeducted() {
        // 来源含乳+花生，目标配方版本只声明乳：并集缺花生，清线凭证不能替代物料过敏原声明。
        long recordId = sourceRecord("P-ASRC", List.of(MILK, PEANUT), "L-ASRC", "50");
        register("RW-A", recordId, "30");
        long targetFiling = approvedFiling("P-ADST", 1);
        registerAllergens("P-ADST", 1, List.of(MILK));
        freshLine("L-ADST");
        // 即使持有覆盖全部过敏原的有效清线凭证，也不能豁免返工料过敏原声明。
        long certificateId = lineService.confirmCertificate(Fixtures.certificate(
                Fixtures.requestId(), "CERT-A", "L-ADST", List.of(MILK, PEANUT), "OP-1", "RV-1",
                T0, T0 + 1)).body().certificateId();

        ControlledProductionRequest request = Fixtures.controlledWithRework(Fixtures.requestId(),
                targetFiling, "L-ADST", 1, "B-A1", "100", certificateId, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-A", "10")));
        assertThatThrownBy(() -> lineService.controlledProduction(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_ALLERGEN_NOT_DECLARED");
                });
        // 整笔零扣：余额不变、无量产记录、凭证未核销、版本未推进。
        assertThat(reworkService.getMaterial("RW-A").consumedKg()).isEqualTo("0");
        assertThat(lineService.getLineProductions("L-ADST").productions()).isEmpty();
        assertThat(lineService.getCertificate(certificateId).status()).isEqualTo("ACTIVE");
        assertThat(lineService.getLine("L-ADST").occupiedVersion()).isEqualTo(1);
    }

    @Test
    void quotaAndParameterBoundaries() {
        long recordId = sourceRecord("P-QSRC", List.of(MILK), "L-QSRC", "50");
        register("RW-Q", recordId, "30");
        long targetFiling = approvedFiling("P-QDST", 1);
        registerAllergens("P-QDST", 1, List.of(MILK));
        freshLine("L-QDST");

        // 返工合计超过总量 20%（20.001 > 20）：422，不靠舍入豁免。
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q1", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-Q", "20.001"))), "REWORK_QUOTA_EXCEEDED",
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 投用重量非正 / 超三位小数 / 总量非正：400。
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q2", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-Q", "0"))), "INVALID_PARAM",
                HttpStatus.BAD_REQUEST);
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q3", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-Q", "1.0005"))), "INVALID_PARAM",
                HttpStatus.BAD_REQUEST);
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q4", "0", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-Q", "1"))), "INVALID_PARAM",
                HttpStatus.BAD_REQUEST);

        // 同一量产料号重复：400。
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q5", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-Q", "1"), Fixtures.reworkUse("RW-Q", "2"))),
                "INVALID_PARAM", HttpStatus.BAD_REQUEST);

        // 超过 10 个不同返工料：400。
        List<ReworkUseDto> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Fixtures.reworkUse("RW-X" + i, "1"));
        }
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q6", "100", null, T0 + 1000, eleven), "INVALID_PARAM",
                HttpStatus.BAD_REQUEST);

        // 不存在的料号：404。
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-QDST", 1, "B-Q7", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-NOPE", "1"))), "REWORK_NOT_FOUND",
                HttpStatus.NOT_FOUND);

        // 全部失败零扣。
        assertThat(reworkService.getMaterial("RW-Q").consumedKg()).isEqualTo("0");
        assertThat(lineService.getLineProductions("L-QDST").productions()).isEmpty();
    }

    @Test
    void multiMaterialInsufficientBalanceRollsBackWholeBatch() {
        long recordA = sourceRecord("P-RA", List.of(MILK), "L-RA", "50");
        long recordB = sourceRecord("P-RB", List.of(EGG), "L-RB", "50");
        register("RW-MA", recordA, "30");
        register("RW-MB", recordB, "5");
        long targetFiling = approvedFiling("P-RDST", 1);
        registerAllergens("P-RDST", 1, List.of(MILK, EGG));
        freshLine("L-RDST");

        // RW-MB 余额 5 不足 10：整笔 422，RW-MA 也零扣。
        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling,
                "L-RDST", 1, "B-R1", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-MA", "10"), Fixtures.reworkUse("RW-MB", "10"))),
                "REWORK_BALANCE_INSUFFICIENT", HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reworkService.getMaterial("RW-MA").consumedKg()).isEqualTo("0");
        assertThat(reworkService.getMaterial("RW-MB").consumedKg()).isEqualTo("0");
        assertThat(lineService.getLineProductions("L-RDST").productions()).isEmpty();
        assertThat(lineService.getLine("L-RDST").occupiedVersion()).isEqualTo(1);

        // 修正为多料合计恰等 20%（12+8=20 但 RW-MB 仅 5）：仍余额不足；
        // 改为 RW-MA 12 + RW-MB 5 = 17 ≤ 20：成功，两料同事务扣减。
        ServiceResult<LineControlResults.ControlledProductionView> ok =
                lineService.controlledProduction(Fixtures.controlledWithRework(
                        Fixtures.requestId(), targetFiling, "L-RDST", 1, "B-R2", "100", null,
                        T0 + 2000,
                        List.of(Fixtures.reworkUse("RW-MA", "12"),
                                Fixtures.reworkUse("RW-MB", "5"))));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(reworkService.getMaterial("RW-MA").consumedKg()).isEqualTo("12");
        assertThat(reworkService.getMaterial("RW-MB").consumedKg()).isEqualTo("5");
        assertThat(reworkService.getMaterial("RW-MB").balanceKg()).isEqualTo("0");
    }

    @Test
    void invalidFilingWithReworkDeductsNothing() {
        long recordId = sourceRecord("P-FSRC", List.of(MILK), "L-FSRC", "50");
        register("RW-F", recordId, "30");
        long cancelledFiling = approvedFiling("P-FDST", 1);
        registerAllergens("P-FDST", 1, List.of(MILK));
        complianceService.cancelFiling(Fixtures.filingRef(cancelledFiling));
        freshLine("L-FDST");

        assertThatRejected(Fixtures.controlledWithRework(Fixtures.requestId(), cancelledFiling,
                "L-FDST", 1, "B-F1", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-F", "10"))), "FILING_CANCELLED",
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reworkService.getMaterial("RW-F").consumedKg()).isEqualTo("0");
    }

    @Test
    void legacyProductionWithoutReworkIsPreserved() {
        long filingId = approvedFiling("P-OLD", 1);
        registerAllergens("P-OLD", 1, List.of(MILK));
        freshLine("L-OLD");
        ServiceResult<LineControlResults.ControlledProductionView> result =
                lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                        filingId, "L-OLD", 1, "B-OLD", "10", null, T0 + 1000));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().reworks()).isEmpty();
        assertThat(lineService.getLineProductions("L-OLD").productions().get(0).reworks())
                .isEmpty();
    }

    @Test
    void failedReworkRequestDoesNotOccupyIdempotentKey() {
        long recordId = sourceRecord("P-ISRC", List.of(MILK), "L-ISRC", "50");
        register("RW-I", recordId, "30");
        long targetFiling = approvedFiling("P-IDST", 1);
        registerAllergens("P-IDST", 1, List.of(MILK));
        freshLine("L-IDST");

        // 先以超额请求失败（422，事务回滚不占键），随后同键修正参数可成功。
        String key = "req-rework-retry";
        assertThatRejected(Fixtures.controlledWithRework(key, targetFiling, "L-IDST", 1,
                "B-I1", "100", null, T0 + 1000,
                List.of(Fixtures.reworkUse("RW-I", "25"))), "REWORK_QUOTA_EXCEEDED",
                HttpStatus.UNPROCESSABLE_ENTITY);
        ServiceResult<LineControlResults.ControlledProductionView> retried =
                lineService.controlledProduction(Fixtures.controlledWithRework(key, targetFiling,
                        "L-IDST", 1, "B-I1", "100", null, T0 + 1000,
                        List.of(Fixtures.reworkUse("RW-I", "20"))));
        assertThat(retried.replayed()).isFalse();
        assertThat(reworkService.getMaterial("RW-I").consumedKg()).isEqualTo("20");
    }

    private void assertThatRejected(ControlledProductionRequest request, String code,
            HttpStatus status) {
        assertThatThrownBy(() -> lineService.controlledProduction(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(status);
                    assertThat(api.getCode()).isEqualTo(code);
                });
    }
}
