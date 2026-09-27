package com.example.starter.compliance;

import com.example.starter.compliance.web.Requests.ConfirmCleaningRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.DirectAdditiveDto;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.FilingRefRequest;
import com.example.starter.compliance.web.Requests.FormulaRefRequest;
import com.example.starter.compliance.web.Requests.IngredientDto;
import com.example.starter.compliance.web.Requests.ProductionReportRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import com.example.starter.compliance.web.Requests.StandardUpdateRequest;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import com.example.starter.compliance.web.Requests.UpsertAllergensRequest;
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

    public static RegisterLineRequest line(String lineCode) {
        return new RegisterLineRequest(requestId(), lineCode);
    }

    public static UpsertAllergensRequest allergens(long formulaId, List<String> allergens,
            Integer expectedVersion) {
        return new UpsertAllergensRequest(requestId(), formulaId, allergens, expectedVersion);
    }

    public static UpsertAllergensRequest allergens(String requestId, long formulaId,
            List<String> allergens, Integer expectedVersion) {
        return new UpsertAllergensRequest(requestId, formulaId, allergens, expectedVersion);
    }

    public static ConfirmCleaningRequest cleaning(String lineCode, List<String> allergens,
            long cleanedAt, int occupancyVersion, String operator, String reviewer) {
        return new ConfirmCleaningRequest(requestId(), lineCode, allergens, cleanedAt,
                occupancyVersion, operator, reviewer);
    }

    public static ConfirmCleaningRequest cleaning(String requestId, String lineCode,
            List<String> allergens, long cleanedAt, int occupancyVersion, String operator,
            String reviewer) {
        return new ConfirmCleaningRequest(requestId, lineCode, allergens, cleanedAt,
                occupancyVersion, operator, reviewer);
    }

    public static ControlledProductionRequest controlledProduction(String lineCode, long filingId,
            String batchNo, String quantityKg, int occupancyVersion, Long voucherId) {
        return new ControlledProductionRequest(requestId(), lineCode, filingId, batchNo,
                new BigDecimal(quantityKg), occupancyVersion, voucherId);
    }

    public static ControlledProductionRequest controlledProduction(String requestId,
            String lineCode, long filingId, String batchNo, String quantityKg,
            int occupancyVersion, Long voucherId) {
        return new ControlledProductionRequest(requestId, lineCode, filingId, batchNo,
                new BigDecimal(quantityKg), occupancyVersion, voucherId);
    }
}
