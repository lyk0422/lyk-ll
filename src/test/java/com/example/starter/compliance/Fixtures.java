package com.example.starter.compliance;

import com.example.starter.compliance.web.Requests.DirectAdditiveDto;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.FilingRefRequest;
import com.example.starter.compliance.web.Requests.FormulaRefRequest;
import com.example.starter.compliance.web.Requests.ConfirmCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.IngredientDto;
import com.example.starter.compliance.web.Requests.ProductionReportRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import com.example.starter.compliance.web.Requests.RegisterReworkLotRequest;
import com.example.starter.compliance.web.Requests.ReworkInputDto;
import com.example.starter.compliance.web.Requests.StandardUpdateRequest;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 测试请求构造工厂；默认使用合成目录 BEV01 风味饮料分类。
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String requestId() {
        return UUID.randomUUID().toString();
    }

    public static DirectAdditiveDto additive(String code, String amount) {
        return new DirectAdditiveDto(code, new BigDecimal(amount));
    }

    public static IngredientDto ingredient(String code, String ratio) {
        return new IngredientDto(code, new BigDecimal(ratio));
    }

    public static SubmitFormulaRequest formula(String productCode, int version,
            List<DirectAdditiveDto> additives, List<IngredientDto> ingredients) {
        return new SubmitFormulaRequest(requestId(), productCode, productCode + "-产品", "BEV01",
                version, additives, ingredients);
    }

    public static SubmitFormulaRequest formulaWithRequestId(String requestId, String productCode,
            int version, List<DirectAdditiveDto> additives, List<IngredientDto> ingredients) {
        return new SubmitFormulaRequest(requestId, productCode, productCode + "-产品", "BEV01",
                version, additives, ingredients);
    }

    public static FormulaRefRequest ref(long formulaId) {
        return new FormulaRefRequest(requestId(), formulaId);
    }

    public static FormulaRefRequest ref(String requestId, long formulaId) {
        return new FormulaRefRequest(requestId, formulaId);
    }

    public static FileFilingRequest file(long formulaId) {
        return new FileFilingRequest(requestId(), formulaId);
    }

    public static FilingRefRequest filingRef(long filingId) {
        return new FilingRefRequest(requestId(), filingId);
    }

    public static FilingRefRequest filingRef(String requestId, long filingId) {
        return new FilingRefRequest(requestId, filingId);
    }

    public static ProductionReportRequest production(long filingId, String batchNo,
            String quantityKg) {
        return new ProductionReportRequest(requestId(), filingId, batchNo,
                new BigDecimal(quantityKg));
    }

    public static StandardUpdateRequest standard(String additiveCode, String categoryCode,
            String maxAmount) {
        return new StandardUpdateRequest(requestId(), additiveCode, categoryCode,
                new BigDecimal(maxAmount));
    }

    public static FormulaAllergenRequest allergens(String requestId, String productCode,
            int formulaVersion, List<String> allergens, int expectedVersion, long registeredAt) {
        return new FormulaAllergenRequest(requestId, productCode, formulaVersion, allergens,
                expectedVersion, registeredAt);
    }

    public static RegisterLineRequest line(String requestId, String lineCode, String lineName,
            String lastProductCode, Integer lastFormulaVersion, long registeredAt) {
        return new RegisterLineRequest(requestId, lineCode, lineName, lastProductCode,
                lastFormulaVersion, registeredAt);
    }

    public static ConfirmCertificateRequest certificate(String requestId, String certificateNo,
            String lineCode, List<String> allergens, String operator, String reviewer,
            long clearedAt, long confirmedAt) {
        return new ConfirmCertificateRequest(requestId, certificateNo, lineCode, allergens,
                operator, reviewer, clearedAt, confirmedAt);
    }

    public static ControlledProductionRequest controlled(String requestId, long filingId,
            String lineCode, int occupiedVersion, String batchNo, String quantityKg,
            Long certificateId, long reportedAt) {
        return new ControlledProductionRequest(requestId, filingId, lineCode, occupiedVersion,
                batchNo, new BigDecimal(quantityKg), certificateId, reportedAt, null);
    }

    public static ControlledProductionRequest controlledWithRework(String requestId, long filingId,
            String lineCode, int occupiedVersion, String batchNo, String quantityKg,
            Long certificateId, long reportedAt, List<ReworkInputDto> reworkInputs) {
        return new ControlledProductionRequest(requestId, filingId, lineCode, occupiedVersion,
                batchNo, new BigDecimal(quantityKg), certificateId, reportedAt, reworkInputs);
    }

    public static ReworkInputDto reworkInput(String lotCode, String quantityKg) {
        return new ReworkInputDto(lotCode, new BigDecimal(quantityKg));
    }

    public static RegisterReworkLotRequest reworkLot(String requestId, String lotCode,
            long productionRecordId, String productCode, String availableKg, long registeredAt) {
        return new RegisterReworkLotRequest(requestId, lotCode, productionRecordId, productCode,
                new BigDecimal(availableKg), 0, registeredAt);
    }
}
