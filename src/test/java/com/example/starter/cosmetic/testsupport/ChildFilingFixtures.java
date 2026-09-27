package com.example.starter.cosmetic.testsupport;

import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildLabelRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSafetyReportRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitRequest;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * 儿童备案合成请求构造工厂。
 */
public final class ChildFilingFixtures {

    private ChildFilingFixtures() {
    }

    /** 低龄档（0~36月）普通配方（无儿童限用成分）。 */
    public static List<FormulationItemRequest> gentleFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("90.0000")),
                new FormulationItemRequest("GLYCERIN", "甘油", new BigDecimal("3.0000")));
    }

    /** 含苯氧乙醇的配方（两档限值 0.4%/1.0%，用于跨档较严判定）。 */
    public static List<FormulationItemRequest> phenoxyethanolFormulation(String concentrationPct) {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("90.0000")),
                new FormulationItemRequest("PHENOXYETHANOL", "苯氧乙醇", new BigDecimal(concentrationPct)));
    }

    /** 低龄档安全报告（覆盖 0~36 月）。 */
    public static ChildSafetyReportRequest lowBandReport(String reportNo) {
        return new ChildSafetyReportRequest(reportNo, "BAND_0_36", 0, 36, "低龄档安全评估结论：安全");
    }

    /** 高龄档安全报告（覆盖 37~144 月）。 */
    public static ChildSafetyReportRequest highBandReport(String reportNo) {
        return new ChildSafetyReportRequest(reportNo, "BAND_37_144", 37, 144, "高龄档安全评估结论：安全");
    }

    /** 低龄档适用年龄标识。 */
    public static ChildLabelRequest lowBandLabel() {
        return new ChildLabelRequest("BAND_0_36", "适用于0~36个月婴幼儿");
    }

    /** 高龄档适用年龄标识。 */
    public static ChildLabelRequest highBandLabel() {
        return new ChildLabelRequest("BAND_37_144", "适用于3~12岁儿童");
    }

    /** 单档（0~36月）材料齐备的儿童备案提交。 */
    public static ChildSubmitRequest lowBandSubmit(String requestId, String productCode) {
        return new ChildSubmitRequest(requestId, productCode, "合成婴儿润肤乳", 0, 36,
                List.of("CH_MOISTURIZE"), gentleFormulation(),
                List.of(lowBandReport("SR-" + requestId)), List.of(lowBandLabel()),
                "包装：置于婴幼儿不可触及处", null);
    }

    /** 跨档（0~144月）材料齐备的儿童备案提交。 */
    public static ChildSubmitRequest crossBandSubmit(String requestId, String productCode) {
        return new ChildSubmitRequest(requestId, productCode, "合成儿童润肤乳", 0, 144,
                List.of("CH_MOISTURIZE", "CH_SOOTHE"), gentleFormulation(),
                List.of(lowBandReport("SR-L-" + requestId), highBandReport("SR-H-" + requestId)),
                List.of(lowBandLabel(), highBandLabel()),
                "包装：儿童化妆品标志", null);
    }

    /** 整体替换式补正请求。 */
    public static ChildCorrectionRequest correctionOf(String requestId, int expectedVersion,
                                                      ChildSubmitRequest base) {
        return new ChildCorrectionRequest(requestId, expectedVersion,
                base.ageLowerMonths(), base.ageUpperMonths(), base.claims(), base.formulation(),
                base.safetyReports(), base.labels(), base.packagingText(), null);
    }
}
