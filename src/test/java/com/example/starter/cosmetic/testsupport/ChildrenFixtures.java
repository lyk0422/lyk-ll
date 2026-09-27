package com.example.starter.cosmetic.testsupport;

import com.example.starter.cosmetic.web.dto.AgeLabelRequest;
import com.example.starter.cosmetic.web.dto.ChildrenSafetyReportRequest;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.SubmitChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.TestReportRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童备案合成请求构造工厂。
 */
public final class ChildrenFixtures {

    private ChildrenFixtures() {
    }

    /** 温和儿童配方（无儿童限用成分风险）。 */
    public static List<FormulationItemRequest> gentleFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("80.0000")),
                new FormulationItemRequest("GLYCERIN", "甘油", new BigDecimal("3.0000")));
    }

    /** 含苯氧乙醇的配方（儿童两档限值 0.4%/1.0%）。 */
    public static List<FormulationItemRequest> phenoxyethanolFormulation(String concentrationPct) {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("80.0000")),
                new FormulationItemRequest("PHENOXYETHANOL", "苯氧乙醇", new BigDecimal(concentrationPct)));
    }

    /** 婴幼儿档（0~36月）安全报告，覆盖 [0,36]。 */
    public static ChildrenSafetyReportRequest infantSafetyReport(String reportNo) {
        return new ChildrenSafetyReportRequest(reportNo, "INFANT", 0, 36,
                LocalDate.of(2026, 1, 15), true, "婴幼儿安全评估通过");
    }

    /** 儿童档（37~144月）安全报告，覆盖 [37,144]。 */
    public static ChildrenSafetyReportRequest childSafetyReport(String reportNo) {
        return new ChildrenSafetyReportRequest(reportNo, "CHILD", 37, 144,
                LocalDate.of(2026, 1, 15), true, "儿童安全评估通过");
    }

    /** 婴幼儿档适用年龄标识，范围 [0,36]。 */
    public static AgeLabelRequest infantLabel(String text) {
        return new AgeLabelRequest("INFANT", text, 0, 36);
    }

    /** 儿童档适用年龄标识，范围 [37,144]。 */
    public static AgeLabelRequest childLabel(String text) {
        return new AgeLabelRequest("CHILD", text, 37, 144);
    }

    /** 跨档（0~144月）儿童备案请求：两档资料都齐。 */
    public static SubmitChildrenFilingRequest crossBandSubmit(String requestId, String productCode) {
        return new SubmitChildrenFilingRequest(requestId, productCode, "合成儿童润肤霜",
                0, 144, gentleFormulation(), List.of("MOISTURIZING"),
                List.of(infantSafetyReport("SR-I-" + requestId), childSafetyReport("SR-C-" + requestId)),
                List.of(infantLabel("适用于0~36月婴幼儿"), childLabel("适用于37~144月儿童")),
                List.of());
    }

    /** 单档（0~36月）儿童备案请求。 */
    public static SubmitChildrenFilingRequest infantSubmit(String requestId, String productCode) {
        return new SubmitChildrenFilingRequest(requestId, productCode, "合成婴幼儿护臀膏",
                0, 36, gentleFormulation(), List.of("MOISTURIZING"),
                List.of(infantSafetyReport("SR-I-" + requestId)),
                List.of(infantLabel("适用于0~36月婴幼儿")),
                List.of());
    }

    /** 成人功效评价报告（不能替代儿童安全报告）。 */
    public static TestReportRequest adultReport(String reportNo) {
        return new TestReportRequest(reportNo, "MOISTURIZING", LocalDate.of(2026, 1, 15),
                true, "成人保湿功效评价通过");
    }
}
