package com.example.starter.cosmetic.child.testsupport;

import com.example.starter.cosmetic.child.web.dto.ChildChangeFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSafetyReportRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童分龄备案合成请求构造工厂。所有写请求携带统一的精确受理时刻
 * （与测试可控时钟 INITIAL_NOW 一致），该时刻参与幂等指纹。
 */
public final class ChildFilingFixtures {

    /** 合成受理时刻(UTC)，对应公示起算日 2026-06-30。 */
    public static final Instant AT = Instant.parse("2026-06-30T08:00:00Z");

    private ChildFilingFixtures() {
    }

    /** 基础儿童配方（无限用成分风险）。 */
    public static List<FormulationItemRequest> waterFormulation() {
        return List.of(
                new FormulationItemRequest("WATER", "水", new BigDecimal("78.0000")),
                new FormulationItemRequest("GLYCERIN", "甘油", new BigDecimal("5.0000")));
    }

    public static ChildSafetyReportRequest youngReport(String reportNo, int from, int to, boolean supported) {
        return new ChildSafetyReportRequest(reportNo, "TIER_0_36", LocalDate.of(2026, 1, 1),
                from, to, supported, supported ? "支持0-36月儿童安全使用" : "不支持");
    }

    public static ChildSafetyReportRequest oldReport(String reportNo, int from, int to, boolean supported) {
        return new ChildSafetyReportRequest(reportNo, "TIER_37_144", LocalDate.of(2026, 1, 1),
                from, to, supported, supported ? "支持37-144月儿童安全使用" : "不支持");
    }

    /** 低龄档 [0,36] 完整首次提交。 */
    public static ChildSubmitFilingRequest youngSubmit(String requestId, String product) {
        return new ChildSubmitFilingRequest(requestId, AT, product, "合成儿童润肤霜",
                0, 36, waterFormulation(), List.of("CHILD_MOISTURIZING"),
                List.of(youngReport("CR-" + requestId, 0, 36, true)),
                "适用年龄 0-36 月（出生至36月龄）");
    }

    /** 跨档 [0,144] 完整首次提交（两档独立报告）。 */
    public static ChildSubmitFilingRequest crossTierSubmit(String requestId, String product) {
        return new ChildSubmitFilingRequest(requestId, AT, product, "合成儿童润肤霜",
                0, 144, waterFormulation(), List.of("CHILD_MOISTURIZING"),
                List.of(youngReport("CR-Y-" + requestId, 0, 36, true),
                        oldReport("CR-O-" + requestId, 37, 144, true)),
                "适用年龄 0-144 月（出生至144月龄）");
    }

    /** 跨档但缺少大龄档报告的提交（形式驳回）。 */
    public static ChildSubmitFilingRequest crossTierMissingOldReport(String requestId, String product) {
        return new ChildSubmitFilingRequest(requestId, AT, product, "合成儿童润肤霜",
                0, 144, waterFormulation(), List.of("CHILD_MOISTURIZING"),
                List.of(youngReport("CR-Y-" + requestId, 0, 36, true)),
                "适用年龄 0-144 月");
    }

    /** 基于跨档缺项请求构造补正（补齐大龄档报告）。 */
    public static ChildCorrectFilingRequest crossTierCorrection(String requestId, int expectedVersion) {
        return new ChildCorrectFilingRequest(requestId, AT, expectedVersion, "合成儿童润肤霜",
                0, 144, waterFormulation(), List.of("CHILD_MOISTURIZING"),
                List.of(youngReport("CR-YC-" + requestId, 0, 36, true),
                        oldReport("CR-OC-" + requestId, 37, 144, true)),
                "适用年龄 0-144 月（出生至144月龄）");
    }

    /** 仅包装文字简易变更。 */
    public static ChildChangeFilingRequest packagingChange(String requestId, int expectedVersion, String text) {
        return new ChildChangeFilingRequest(requestId, AT, expectedVersion, null, null, null,
                null, null, null, null, text);
    }
}
