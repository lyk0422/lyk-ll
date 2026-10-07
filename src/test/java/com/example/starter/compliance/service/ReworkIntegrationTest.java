package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.RegisterReworkLotRequest;
import com.example.starter.compliance.web.Requests.ReworkInputDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 返工料投用主流程与失败分支 H2 测试：
 * 登记冻结来源配方版本及过敏原集合、同来源只登记一次、不能引用别人的配方记录；
 * 过敏原缺项（清线凭证不能豁免）、20% 边界（恰等允许）、多料任一不足整笔零扣、
 * 备案失效零扣、料账勾稽查询与幂等重放。
 */
class ReworkIntegrationTest extends AbstractIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String MILK = "ALG_MILK";
    private static final String EGG = "ALG_EGG";

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

    /** 经受控量产产生一条成功量产记录（来源配方版本已登记过敏原），返回记录 id。 */
    private long sourceRecord(String productCode, String lineCode, List<String> allergens,
            long at) {
        long filingId = approvedFiling(productCode, 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), productCode,
                1, allergens, 0, at));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), lineCode, lineCode + "-线",
                null, null, at));
        return lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(), filingId,
                lineCode, 1, "B-SRC-" + productCode, "50", null, at + 1000))
                .body().productionRecordId();
    }

    /** 目标配方备案 + 全新线路（无遗留过敏原，不需凭证），返回备案 id。 */
    private long targetOnFreshLine(String productCode, String lineCode,
            List<String> declaredAllergens, long at) {
        long filingId = approvedFiling(productCode, 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), productCode,
                1, declaredAllergens, 0, at));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), lineCode, lineCode + "-线",
                null, null, at));
        return filingId;
    }

    private LineControlResults.ReworkLotView registerLot(String lotCode, long recordId,
            String productCode, String availableKg, long at) {
        return lineService.registerReworkLot(Fixtures.reworkLot(Fixtures.requestId(), lotCode,
                recordId, productCode, availableKg, at)).body();
    }

    @Test
    void registrationFreezesSourceAndEnforcesUniquenessAndOwnership() {
        long recordId = sourceRecord("P-R1", "L-R1", List.of(MILK, EGG), T0);

        // 成功登记：冻结来源配方版本与过敏原集合，料账版本从 1 开始，余额等于登记量。
        LineControlResults.ReworkLotView lot = registerLot("RW-1", recordId, "P-R1", "25.500", T0);
        assertThat(lot.lotCode()).isEqualTo("RW-1");
        assertThat(lot.productionRecordId()).isEqualTo(recordId);
        assertThat(lot.productCode()).isEqualTo("P-R1");
        assertThat(lot.formulaVersion()).isEqualTo(1);
        assertThat(lot.allergens()).containsExactly(EGG, MILK);
        assertThat(lot.availableKg()).isEqualTo("25.5");
        assertThat(lot.consumedKg()).isEqualTo("0");
        assertThat(lot.balanceKg()).isEqualTo("25.5");
        assertThat(lot.version()).isEqualTo(1);

        // 不能引用别人的配方记录：声明产品与来源记录实际产品不一致 → 422。
        RegisterReworkLotRequest mismatch = Fixtures.reworkLot(Fixtures.requestId(), "RW-2",
                recordId, "P-OTHER", "1", T0);
        assertThatThrownBy(() -> lineService.registerReworkLot(mismatch))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_SOURCE_MISMATCH");
                });

        // 重复料号 → 409；同一来源记录再登记 → 409。
        assertThatThrownBy(() -> lineService.registerReworkLot(Fixtures.reworkLot(
                Fixtures.requestId(), "RW-1", recordId, "P-R1", "1", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_LOT_EXISTS"));
        assertThatThrownBy(() -> lineService.registerReworkLot(Fixtures.reworkLot(
                Fixtures.requestId(), "RW-3", recordId, "P-R1", "1", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_SOURCE_ALREADY_REGISTERED"));

        // 来源记录不存在 → 404；登记量非正数 / 超三位小数 / expectedVersion 非 0 → 400。
        assertThatThrownBy(() -> lineService.registerReworkLot(Fixtures.reworkLot(
                Fixtures.requestId(), "RW-4", 999_999L, "P-R1", "1", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> lineService.registerReworkLot(Fixtures.reworkLot(
                Fixtures.requestId(), "RW-4", recordId, "P-R1", "0", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> lineService.registerReworkLot(Fixtures.reworkLot(
                Fixtures.requestId(), "RW-4", recordId, "P-R1", "1.0001", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        RegisterReworkLotRequest badVersion = new RegisterReworkLotRequest(Fixtures.requestId(),
                "RW-4", recordId, "P-R1", new BigDecimal("1"), 1, T0);
        assertThatThrownBy(() -> lineService.registerReworkLot(badVersion))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void registrationRequiresSourceAllergenRegistrationToFreeze() {
        // 旧入口量产记录（来源配方版本未登记过敏原集合）：无法冻结 → 422。
        long filingId = approvedFiling("P-NOREG", 1);
        complianceService.reportProduction(Fixtures.production(filingId, "B-LEGACY", "10"));
        long recordId = repository.findProductionRecordsByFiling(filingId).get(0).id();

        RegisterReworkLotRequest request = Fixtures.reworkLot(Fixtures.requestId(), "RW-NOREG",
                recordId, "P-NOREG", "5", T0);
        assertThatThrownBy(() -> lineService.registerReworkLot(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_ALLERGEN_NOT_REGISTERED");
                });
    }

    @Test
    void registrationIdempotentReplayAndDifferentParamsConflict() {
        long recordId = sourceRecord("P-RID", "L-RID", List.of(MILK), T0);
        RegisterReworkLotRequest request = Fixtures.reworkLot("req-rework-1", "RW-ID", recordId,
                "P-RID", "10", T0);
        ServiceResult<LineControlResults.ReworkLotView> first =
                lineService.registerReworkLot(request);
        ServiceResult<LineControlResults.ReworkLotView> replay =
                lineService.registerReworkLot(request);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().lotId()).isEqualTo(first.body().lotId());

        // 同键异参 → 409；失败不占键：先以错误产品失败，修正后同键可成功。
        RegisterReworkLotRequest different = Fixtures.reworkLot("req-rework-1", "RW-ID", recordId,
                "P-RID", "11", T0);
        assertThatThrownBy(() -> lineService.registerReworkLot(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REQUEST_PARAM_CONFLICT"));

        long record2 = sourceRecord("P-RID2", "L-RID2", List.of(MILK), T0);
        RegisterReworkLotRequest badFirst = Fixtures.reworkLot("req-rework-2", "RW-ID2", record2,
                "P-WRONG", "5", T0);
        assertThatThrownBy(() -> lineService.registerReworkLot(badFirst))
                .isInstanceOf(ApiException.class);
        ServiceResult<LineControlResults.ReworkLotView> corrected =
                lineService.registerReworkLot(Fixtures.reworkLot("req-rework-2", "RW-ID2", record2,
                        "P-RID2", "5", T0));
        assertThat(corrected.replayed()).isFalse();
        assertThat(corrected.body().lotCode()).isEqualTo("RW-ID2");
    }

    @Test
    void allergenGapRejectedEvenWithValidClearanceCertificate() {
        // 来源含乳配方量产记录 → 返工料 R1 冻结 [ALG_MILK]。
        long recordId = sourceRecord("P-MILK", "L-SRC", List.of(MILK), T0);
        registerLot("RW-MILK", recordId, "P-MILK", "25", T0);

        // 目标配方只声明蛋，未声明乳；线路最近量产为含乳配方（遗留乳需凭证）。
        long targetFiling = approvedFiling("P-PLAIN", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-PLAIN",
                1, List.of(EGG), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-DST", "目标线",
                "P-MILK", 1, T0));
        long certificateId = lineService.confirmCertificate(Fixtures.certificate(
                Fixtures.requestId(), "CERT-DST", "L-DST", List.of(MILK), "OP-1", "RV-1",
                T0 + 100, T0 + 200)).body().certificateId();

        // 清线凭证只证明线路清洁，不能替代返工料过敏原声明：仍 422 且整笔零扣。
        ControlledProductionRequest request = Fixtures.controlledWithRework(Fixtures.requestId(),
                targetFiling, "L-DST", 1, "B-1", "100", certificateId, T0 + 300,
                List.of(Fixtures.reworkInput("RW-MILK", "5")));
        assertThatThrownBy(() -> lineService.controlledProduction(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_ALLERGEN_UNDECLARED");
                });

        LineControlResults.ReworkLotView lot =
                lineService.getReworkLotLedger("RW-MILK").lot();
        assertThat(lot.consumedKg()).isEqualTo("0");
        assertThat(lot.balanceKg()).isEqualTo("25");
        assertThat(lot.version()).isEqualTo(1);
        // 凭证未被消耗、线路版本未推进、无量产记录。
        assertThat(lineService.getCertificate(certificateId).status()).isEqualTo("ACTIVE");
        assertThat(lineService.getLine("L-DST").occupiedVersion()).isEqualTo(1);
        assertThat(lineService.getLineProductions("L-DST").productions()).isEmpty();
    }

    @Test
    void ratioBoundaryExactlyTwentyPercentAllowedAndExceededRejected() {
        long recordId = sourceRecord("P-BND", "L-BND", List.of(MILK), T0);
        registerLot("RW-BND", recordId, "P-BND", "25", T0);
        long targetFiling = targetOnFreshLine("P-TBND", "L-TBND", List.of(MILK), T0);

        // 100 公斤量产投 20 公斤返工料：恰等 20% 允许。
        ServiceResult<LineControlResults.ControlledProductionView> accepted =
                lineService.controlledProduction(Fixtures.controlledWithRework(
                        Fixtures.requestId(), targetFiling, "L-TBND", 1, "B-100", "100", null,
                        T0 + 1000, List.of(Fixtures.reworkInput("RW-BND", "20"))));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().reworkConsumptions()).hasSize(1);
        assertThat(accepted.body().reworkConsumptions().get(0).lotCode()).isEqualTo("RW-BND");
        assertThat(accepted.body().reworkConsumptions().get(0).quantityKg()).isEqualTo("20");

        LineControlResults.ReworkLotView lot = lineService.getReworkLotLedger("RW-BND").lot();
        assertThat(lot.consumedKg()).isEqualTo("20");
        assertThat(lot.balanceKg()).isEqualTo("5");
        assertThat(lot.version()).isEqualTo(2);

        // 20.001 公斤 > 20% 上限：422，精确比较不靠舍入，零扣。
        ControlledProductionRequest exceeded = Fixtures.controlledWithRework(Fixtures.requestId(),
                targetFiling, "L-TBND", 2, "B-101", "100", null, T0 + 2000,
                List.of(Fixtures.reworkInput("RW-BND", "20.001")));
        assertThatThrownBy(() -> lineService.controlledProduction(exceeded))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("REWORK_RATIO_EXCEEDED");
                });
        LineControlResults.ReworkLotView after = lineService.getReworkLotLedger("RW-BND").lot();
        assertThat(after.consumedKg()).isEqualTo("20");
        assertThat(after.version()).isEqualTo(2);
        assertThat(lineService.getLine("L-TBND").occupiedVersion()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-TBND").productions()).hasSize(1);
    }

    @Test
    void multiLotAnyInsufficientRollsBackAllAndInvalidFilingDeductsNothing() {
        long record1 = sourceRecord("P-M1", "L-M1", List.of(MILK), T0);
        long record2 = sourceRecord("P-M2", "L-M2", List.of(MILK), T0);
        registerLot("RW-A", record1, "P-M1", "10", T0);
        registerLot("RW-B", record2, "P-M2", "5", T0);
        long targetFiling = targetOnFreshLine("P-TM", "L-TM", List.of(MILK), T0);

        // 两料合计 16 ≤ 20，但 RW-B 余额 5 不足 8：任一不足整笔零扣。
        ControlledProductionRequest insufficient = Fixtures.controlledWithRework(
                Fixtures.requestId(), targetFiling, "L-TM", 1, "B-1", "100", null, T0 + 1000,
                List.of(Fixtures.reworkInput("RW-A", "8"), Fixtures.reworkInput("RW-B", "8")));
        assertThatThrownBy(() -> lineService.controlledProduction(insufficient))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REWORK_QUOTA_INSUFFICIENT"));
        assertThat(lineService.getReworkLotLedger("RW-A").lot().consumedKg()).isEqualTo("0");
        assertThat(lineService.getReworkLotLedger("RW-A").lot().version()).isEqualTo(1);
        assertThat(lineService.getReworkLotLedger("RW-B").lot().consumedKg()).isEqualTo("0");
        assertThat(lineService.getLine("L-TM").occupiedVersion()).isEqualTo(1);
        assertThat(lineService.getLineProductions("L-TM").productions()).isEmpty();

        // 备案注销后投返工料：备案失效整笔零扣。
        complianceService.cancelFiling(Fixtures.filingRef(targetFiling));
        ControlledProductionRequest cancelled = Fixtures.controlledWithRework(
                Fixtures.requestId(), targetFiling, "L-TM", 1, "B-2", "100", null, T0 + 2000,
                List.of(Fixtures.reworkInput("RW-A", "5")));
        assertThatThrownBy(() -> lineService.controlledProduction(cancelled))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("FILING_CANCELLED"));
        assertThat(lineService.getReworkLotLedger("RW-A").lot().consumedKg()).isEqualTo("0");
        assertThat(lineService.getReworkLotLedger("RW-A").lot().version()).isEqualTo(1);
    }

    @Test
    void reworkInputValidationRejectsBadShape() {
        long recordId = sourceRecord("P-V", "L-V", List.of(MILK), T0);
        registerLot("RW-V", recordId, "P-V", "50", T0);
        long targetFiling = targetOnFreshLine("P-TV", "L-TV", List.of(MILK), T0);

        // 11 个不同料号 → 400。
        List<ReworkInputDto> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Fixtures.reworkInput("RW-X" + i, "1"));
        }
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-11", "100", null, T0 + 1000, eleven)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 重复料号 → 400。
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-DUP", "100", null, T0 + 1000,
                        List.of(Fixtures.reworkInput("RW-V", "1"),
                                Fixtures.reworkInput("RW-V", "2")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 投用重量非正数 / 超三位小数 / 总量为零 → 400。
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-Z", "100", null, T0 + 1000,
                        List.of(Fixtures.reworkInput("RW-V", "0")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-4D", "100", null, T0 + 1000,
                        List.of(Fixtures.reworkInput("RW-V", "1.0001")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-T0", "0", null, T0 + 1000,
                        List.of(Fixtures.reworkInput("RW-V", "1")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 未登记料号 → 404。
        assertThatThrownBy(() -> lineService.controlledProduction(
                Fixtures.controlledWithRework(Fixtures.requestId(), targetFiling, "L-TV", 1,
                        "B-GHOST", "100", null, T0 + 1000,
                        List.of(Fixtures.reworkInput("RW-GHOST", "1")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("REWORK_LOT_NOT_FOUND");
                });

        // 全部失败零扣。
        assertThat(lineService.getReworkLotLedger("RW-V").lot().consumedKg()).isEqualTo("0");
        assertThat(lineService.getLine("L-TV").occupiedVersion()).isEqualTo(1);
    }

    @Test
    void ledgerQueryReconcilesSourceConsumptionAndBalance() {
        long record1 = sourceRecord("P-Q1", "L-Q1", List.of(MILK), T0);
        long record2 = sourceRecord("P-Q2", "L-Q2", List.of(EGG), T0);
        registerLot("RW-Q1", record1, "P-Q1", "10", T0);
        registerLot("RW-Q2", record2, "P-Q2", "8", T0);
        long targetFiling = targetOnFreshLine("P-TQ", "L-TQ", List.of(MILK, EGG), T0);

        ServiceResult<LineControlResults.ControlledProductionView> accepted =
                lineService.controlledProduction(Fixtures.controlledWithRework(
                        Fixtures.requestId(), targetFiling, "L-TQ", 1, "B-Q", "100", null,
                        T0 + 1000, List.of(Fixtures.reworkInput("RW-Q1", "6"),
                                Fixtures.reworkInput("RW-Q2", "8"))));
        assertThat(accepted.status()).isEqualTo(200);
        long productionRecordId = accepted.body().productionRecordId();

        // 勾稽：来源、原量、消费与余额一致。
        LineControlResults.ReworkLotLedgerView ledger1 =
                lineService.getReworkLotLedger("RW-Q1");
        assertThat(ledger1.lot().productionRecordId()).isEqualTo(record1);
        assertThat(ledger1.lot().availableKg()).isEqualTo("10");
        assertThat(ledger1.lot().consumedKg()).isEqualTo("6");
        assertThat(ledger1.lot().balanceKg()).isEqualTo("4");
        assertThat(ledger1.lot().version()).isEqualTo(2);
        assertThat(ledger1.consumptions()).hasSize(1);
        assertThat(ledger1.consumptions().get(0).productionRecordId())
                .isEqualTo(productionRecordId);
        assertThat(ledger1.consumptions().get(0).quantityKg()).isEqualTo("6");

        LineControlResults.ReworkLotLedgerView ledger2 =
                lineService.getReworkLotLedger("RW-Q2");
        assertThat(ledger2.lot().consumedKg()).isEqualTo("8");
        assertThat(ledger2.lot().balanceKg()).isEqualTo("0");

        // 线路量产历史携带返工消费明细，可逐笔勾稽。
        LineControlResults.LineProductionView history =
                lineService.getLineProductions("L-TQ");
        assertThat(history.productions()).hasSize(1);
        assertThat(history.productions().get(0).reworkConsumptions())
                .extracting(LineControlResults.ReworkConsumptionView::lotCode)
                .containsExactly("RW-Q1", "RW-Q2");
    }

    @Test
    void productionIdempotentReplayDoesNotDoubleDeduct() {
        long recordId = sourceRecord("P-PID", "L-PID", List.of(MILK), T0);
        registerLot("RW-PID", recordId, "P-PID", "20", T0);
        long targetFiling = targetOnFreshLine("P-TPID", "L-TPID", List.of(MILK), T0);

        ControlledProductionRequest request = Fixtures.controlledWithRework("req-rw-prod-1",
                targetFiling, "L-TPID", 1, "B-P", "100", null, T0 + 1000,
                List.of(Fixtures.reworkInput("RW-PID", "10")));
        ServiceResult<LineControlResults.ControlledProductionView> first =
                lineService.controlledProduction(request);
        ServiceResult<LineControlResults.ControlledProductionView> replay =
                lineService.controlledProduction(request);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().productionRecordId())
                .isEqualTo(first.body().productionRecordId());
        assertThat(replay.body().reworkConsumptions())
                .isEqualTo(first.body().reworkConsumptions());

        // 重放不二次扣减：消费与版本保持首次结果。
        LineControlResults.ReworkLotView lot = lineService.getReworkLotLedger("RW-PID").lot();
        assertThat(lot.consumedKg()).isEqualTo("10");
        assertThat(lot.version()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-TPID").productions()).hasSize(1);

        // 同键异参 → 409。
        ControlledProductionRequest different = Fixtures.controlledWithRework("req-rw-prod-1",
                targetFiling, "L-TPID", 1, "B-P", "100", null, T0 + 1000,
                List.of(Fixtures.reworkInput("RW-PID", "11")));
        assertThatThrownBy(() -> lineService.controlledProduction(different))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REQUEST_PARAM_CONFLICT"));
    }

    @Test
    void productionWithoutReworkKeepsLegacyBehavior() {
        long targetFiling = targetOnFreshLine("P-LEG", "L-LEG", List.of(MILK), T0);
        ServiceResult<LineControlResults.ControlledProductionView> result =
                lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                        targetFiling, "L-LEG", 1, "B-LEG", "10", null, T0 + 1000));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().reworkConsumptions()).isEmpty();
        assertThat(lineService.getLine("L-LEG").occupiedVersion()).isEqualTo(2);
    }
}
