package com.example.starter.cosmetic.testsupport;

import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildBandDossierRequest;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import com.example.starter.cosmetic.web.dto.TestReportRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 合成请求构造工厂。
 */
public final class FilingFixtures {

    private FilingFixtures() {
    }

    /** 普通保湿配方（无限用成分风险）。 */
    public static List<FormulationItemRequest> ordinaryFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("78.0000")),
                new FormulationItemRequest("GLYCERIN", "甘油", new BigDecimal("5.0000")));
    }

    /** 含水杨酸恰等限值 2% 的配方。 */
    public static List<FormulationItemRequest> salicylicAtLimitFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("76.0000")),
                new FormulationItemRequest("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0000")));
    }

    /** 防晒配方（含二氧化钛恰等限值 25%）。 */
    public static List<FormulationItemRequest> sunscreenFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("50.0000")),
                new FormulationItemRequest("TITANIUM_DIOXIDE", "二氧化钛", new BigDecimal("25.0000")));
    }

    /** 防晒功效评价报告。 */
    public static TestReportRequest sunscreenReport(String reportNo, LocalDate issuedDate, boolean supported) {
        return new TestReportRequest(reportNo, "SUNSCREEN", issuedDate, supported,
                supported ? "结论支持防晒功效" : "结论不支持防晒功效");
    }

    /** 普通化妆品首次备案请求。 */
    public static SubmitFilingRequest ordinarySubmit(String requestId, String productCode) {
        return new SubmitFilingRequest(requestId, productCode, "合成保湿乳",
                ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                null, null, null, null);
    }

    /** 防晒特殊化妆品首次备案请求。 */
    public static SubmitFilingRequest sunscreenSubmit(String requestId, String productCode,
                                                      LocalDate reportIssuedDate, boolean supported) {
        return new SubmitFilingRequest(requestId, productCode, "合成防晒霜",
                sunscreenFormulation(), List.of("SUNSCREEN"),
                List.of(sunscreenReport("R-" + requestId, reportIssuedDate, supported)),
                null, null, null, null);
    }

    /** 仅包装文字简易变更。 */
    public static ChangeFilingRequest packagingChange(String requestId, String packagingText) {
        return new ChangeFilingRequest(requestId, null, null, null, null, packagingText,
                null, null, null, null, null);
    }

    // ============================ 儿童备案合成材料 ============================

    /** 0~36 月档儿童安全报告+适用年龄标识。 */
    public static ChildBandDossierRequest band036Dossier(String reportNo, int covLower, int covUpper) {
        return new ChildBandDossierRequest("BAND_0_36", reportNo, covLower, covUpper, true,
                "结论支持0-36月婴幼儿使用安全", "适用于0-36个月婴幼儿");
    }

    /** 37~144 月档儿童安全报告+适用年龄标识。 */
    public static ChildBandDossierRequest band37144Dossier(String reportNo, int covLower, int covUpper) {
        return new ChildBandDossierRequest("BAND_37_144", reportNo, covLower, covUpper, true,
                "结论支持37-144月儿童使用安全", "适用于37-144个月儿童");
    }

    /** 单档（0~36 月）儿童备案提交请求。 */
    public static SubmitFilingRequest childSubmitBand036(String requestId, String productCode) {
        return new SubmitFilingRequest(requestId, productCode, "合成婴儿保湿霜",
                ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 36, List.of(band036Dossier("CS-" + requestId, 0, 36)));
    }

    /** 跨档（0~144 月）儿童备案提交请求：两档资料都齐。 */
    public static SubmitFilingRequest childSubmitCrossBand(String requestId, String productCode) {
        return new SubmitFilingRequest(requestId, productCode, "合成儿童保湿乳",
                ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                "CHILD", 0, 144,
                List.of(band036Dossier("CS-A-" + requestId, 0, 36),
                        band37144Dossier("CS-B-" + requestId, 37, 144)));
    }
}
