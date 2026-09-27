package com.example.starter.compliance;

import com.example.starter.compliance.web.Requests;
import com.example.starter.compliance.web.Requests.DirectAdditiveDto;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.FilingRefRequest;
import com.example.starter.compliance.web.Requests.FormulaRefRequest;
import com.example.starter.compliance.web.Requests.IngredientDto;
import com.example.starter.compliance.web.Requests.ProductionReportRequest;
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

    // -------- 换配方清线 --------

    public static Requests.FormulaAllergenRequest allergensReg(long formulaId,
            Integer expectedVersion, List<String> allergens) {
        return new Requests.FormulaAllergenRequest(requestId(), formulaId, expectedVersion,
                allergens);
    }

    public static Requests.FormulaAllergenRequest allergensReg(String requestId, long formulaId,
            Integer expectedVersion, List<String> allergens) {
        return new Requests.FormulaAllergenRequest(requestId, formulaId, expectedVersion,
                allergens);
    }
    public static Requests.ProductionLineRequest line(String lineCode, String lineName) {
        return new Requests.ProductionLineRequest(requestId(), lineCode, lineName);
    }

    public static Requests.ClearanceCertificateRequest clearance(String lineCode,
            String operatorId, String reviewerId, long cleanedAt, long confirmedAt,
            List<String> coveredAllergens) {
        return new Requests.ClearanceCertificateRequest(requestId(), lineCode, operatorId,
                reviewerId, cleanedAt, confirmedAt, coveredAllergens);
    }

    public static Requests.ClearanceCertificateRequest clearance(String requestId, String lineCode,
            String operatorId, String reviewerId, long cleanedAt, long confirmedAt,
            List<String> coveredAllergens) {
        return new Requests.ClearanceCertificateRequest(requestId, lineCode, operatorId,
                reviewerId, cleanedAt, confirmedAt, coveredAllergens);
    }

    public static Requests.ControlledProductionRequest controlled(String lineCode, long filingId,
            String batchNo, int occupiedVersion, long occurredAt, Long certificateId) {
        return new Requests.ControlledProductionRequest(requestId(), lineCode, filingId, batchNo,
                new BigDecimal("100"), occupiedVersion, occurredAt, certificateId);
    }

    public static Requests.ControlledProductionRequest controlled(String requestId, String lineCode,
            long filingId, String batchNo, String quantityKg, int occupiedVersion, long occurredAt,
            Long certificateId) {
        return new Requests.ControlledProductionRequest(requestId, lineCode, filingId, batchNo,
                new BigDecimal(quantityKg), occupiedVersion, occurredAt, certificateId);
    }
}
