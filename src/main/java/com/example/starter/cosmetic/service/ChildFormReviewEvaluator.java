package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.AgeBandRule;
import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.RestrictedIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.port.CatalogPort;
import com.example.starter.cosmetic.port.ReportPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 儿童备案分龄审查规则计算器（无外部状态，可单测复算）。
 *
 * <p>规则口径（任一缺项一次列全并零生效）：</p>
 * <ol>
 *   <li>DOC_COMPLETE：备案资料齐全（产品名、配方、宣称均非空）。</li>
 *   <li>CHILD_AGE_RANGE：申请年龄为整数月闭区间，0≤下限≤上限≤144。</li>
 *   <li>CHILD_CLAIM_CATALOG：只允许合成目录中的儿童功效（childAllowed=1 且支持宣称）。</li>
 *   <li>CHILD_BAND_MATERIAL：申请区间落入的每个分龄档须材料齐备——该档独立安全报告
 *       （结论支持、明确覆盖申请年龄范围在该档内的部分）与适用年龄标识
 *       （非空且明示申请年龄范围在该档内的上下限）；跨档须两档资料都齐。</li>
 *   <li>CHILD_REPORT_INDEPENDENT：儿童安全报告独立于成人功效评价试验报告，
 *       成人报告编号不得充当儿童安全报告；各档报告编号不得复用。</li>
 *   <li>CHILD_RESTRICTED_CONCENTRATION：限用成分浓度按涉及分龄档限值较严者（取小）判定，
 *       恰等限值合格；档未设儿童限值时回退成人限值。</li>
 * </ol>
 */
public class ChildFormReviewEvaluator {

    /** 规则阶段标识。 */
    public static final String STAGE_FORM = "FORM";

    /** 儿童年龄上限（整数月，12 周岁）。 */
    public static final int MAX_AGE_MONTHS = 144;

    private final CatalogPort catalogPort;
    private final ReportPort reportPort;

    public ChildFormReviewEvaluator(CatalogPort catalogPort, ReportPort reportPort) {
        this.catalogPort = catalogPort;
        this.reportPort = reportPort;
    }

    /**
     * 执行儿童备案分龄审查。
     *
     * @param productName   产品名称
     * @param formulation   配方快照
     * @param claims        功效宣称编码
     * @param ageLower      申请年龄下限（整数月，含）
     * @param ageUpper      申请年龄上限（整数月，含）
     * @param dossiers      分龄档材料清单（安全报告+适用年龄标识）
     * @param bands         当前分龄规则版本下的分龄档定义
     * @param ruleVersion   受理冻结的分龄规则版本
     * @param nowUtc        明细记录时刻(UTC)
     * @param filingNo      备案编号（驳回件亦分配编号）
     * @param versionNo     版本号
     */
    public FormReviewEvaluator.Result evaluate(String productName,
                                               List<FormulationIngredient> formulation,
                                               List<String> claims,
                                               Integer ageLower,
                                               Integer ageUpper,
                                               List<ChildBandDossier> dossiers,
                                               List<AgeBandRule> bands,
                                               String ruleVersion,
                                               Instant nowUtc,
                                               String filingNo,
                                               int versionNo) {
        List<ReviewDetail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        evaluateDocuments(productName, formulation, claims, details, reasons, nowUtc, filingNo, versionNo);
        boolean ageOk = evaluateAgeRange(ageLower, ageUpper, details, reasons, nowUtc, filingNo, versionNo);
        evaluateClaimCatalog(claims, details, reasons, nowUtc, filingNo, versionNo);

        List<AgeBandRule> applicable = ageOk
                ? bands.stream().filter(b -> b.intersects(ageLower, ageUpper)).toList()
                : List.of();
        evaluateBandMaterials(ageLower, ageUpper, dossiers, bands, applicable,
                details, reasons, nowUtc, filingNo, versionNo);
        evaluateReportIndependence(dossiers, details, reasons, nowUtc, filingNo, versionNo);
        evaluateConcentration(formulation, applicable, details, reasons, nowUtc, filingNo, versionNo);

        return new FormReviewEvaluator.Result(reasons.isEmpty(), List.copyOf(details), List.copyOf(reasons));
    }

    private void evaluateDocuments(String productName,
                                   List<FormulationIngredient> formulation,
                                   List<String> claims,
                                   List<ReviewDetail> details,
                                   List<String> reasons,
                                   Instant now,
                                   String filingNo,
                                   int versionNo) {
        boolean nameOk = productName != null && !productName.isBlank();
        boolean formulationOk = formulation != null && !formulation.isEmpty();
        boolean claimsOk = claims != null && !claims.isEmpty();
        boolean passed = nameOk && formulationOk && claimsOk;
        String detail = String.format(
                "资料齐全性：产品名称=%s，配方条目数=%d，功效宣称数=%d",
                nameOk ? "齐全" : "缺失",
                formulation == null ? 0 : formulation.size(),
                claims == null ? 0 : claims.size());
        add(details, reasons, now, filingNo, versionNo, "DOC_COMPLETE", passed, detail);
    }

    private boolean evaluateAgeRange(Integer ageLower,
                                     Integer ageUpper,
                                     List<ReviewDetail> details,
                                     List<String> reasons,
                                     Instant now,
                                     String filingNo,
                                     int versionNo) {
        boolean passed = ageLower != null && ageUpper != null
                && ageLower >= 0 && ageUpper <= MAX_AGE_MONTHS && ageLower <= ageUpper;
        String detail = String.format(
                "申请年龄范围(整数月闭区间)：[%s, %s]，口径 0≤下限≤上限≤%d → %s",
                String.valueOf(ageLower), String.valueOf(ageUpper), MAX_AGE_MONTHS,
                passed ? "合法" : "非法（缺省、越界或下限大于上限）");
        add(details, reasons, now, filingNo, versionNo, "CHILD_AGE_RANGE", passed, detail);
        return passed;
    }

    private void evaluateClaimCatalog(List<String> claims,
                                      List<ReviewDetail> details,
                                      List<String> reasons,
                                      Instant now,
                                      String filingNo,
                                      int versionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            Optional<EfficacyCatalog> cat = catalogPort.findEfficacy(code);
            if (cat.isEmpty()) {
                passed = false;
                problems.add("功效编码不在合成目录: " + code);
                continue;
            }
            EfficacyCatalog c = cat.get();
            if (!c.supported()) {
                passed = false;
                problems.add("不支持的功效宣称，拒绝: " + code + "(" + c.name() + ")");
            } else if (!c.childAllowed()) {
                passed = false;
                problems.add("非儿童合成目录功效，儿童备案不允许宣称: " + code + "(" + c.name() + ")");
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_CLAIM_CATALOG", passed,
                "儿童功效目录校验（只允许合成目录中的儿童功效）："
                        + (passed ? "全部宣称在儿童合成目录内" : String.join("；", problems)));
    }

    private void evaluateBandMaterials(Integer ageLower,
                                       Integer ageUpper,
                                       List<ChildBandDossier> dossiers,
                                       List<AgeBandRule> allBands,
                                       List<AgeBandRule> applicable,
                                       List<ReviewDetail> details,
                                       List<String> reasons,
                                       Instant now,
                                       String filingNo,
                                       int versionNo) {
        List<ChildBandDossier> safeDossiers = dossiers == null ? List.of() : dossiers;
        Map<String, ChildBandDossier> byBand = new HashMap<>();
        Set<String> knownBands = new HashSet<>();
        allBands.forEach(b -> knownBands.add(b.bandCode()));

        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (ChildBandDossier d : safeDossiers) {
            if (!knownBands.contains(d.bandCode())) {
                passed = false;
                problems.add("未知分龄档编码: " + d.bandCode());
            }
            if (byBand.putIfAbsent(d.bandCode(), d) != null) {
                passed = false;
                problems.add("分龄档材料重复提交: " + d.bandCode());
            }
        }

        for (AgeBandRule band : applicable) {
            String seg = "[" + band.coveredLower(ageLower) + ", " + band.coveredUpper(ageUpper) + "]月";
            ChildBandDossier dossier = byBand.get(band.bandCode());
            if (dossier == null) {
                passed = false;
                problems.add("分龄档 " + band.bandCode() + " 缺独立安全报告与适用年龄标识（覆盖段 "
                        + seg + "）");
                continue;
            }
            if (dossier.report() == null) {
                passed = false;
                problems.add("分龄档 " + band.bandCode() + " 缺独立安全报告");
            } else {
                var report = dossier.report();
                if (!band.bandCode().equals(report.bandCode())) {
                    passed = false;
                    problems.add("报告 " + report.reportNo() + " 对应分龄档 " + report.bandCode()
                            + " 与材料档 " + band.bandCode() + " 不一致");
                }
                if (!report.conclusionSupported()) {
                    passed = false;
                    problems.add("报告 " + report.reportNo() + " 结论为不支持该年龄段使用安全");
                }
                boolean covers = report.coverageLowerMonths() <= band.coveredLower(ageLower)
                        && report.coverageUpperMonths() >= band.coveredUpper(ageUpper);
                if (!covers) {
                    passed = false;
                    problems.add("报告 " + report.reportNo() + " 覆盖范围 ["
                            + report.coverageLowerMonths() + ", " + report.coverageUpperMonths()
                            + "]月 未明确覆盖申请年龄范围在该档内的部分 " + seg);
                }
            }
            String label = dossier.labelText();
            if (label == null || label.isBlank()) {
                passed = false;
                problems.add("分龄档 " + band.bandCode() + " 缺适用年龄标识文本");
            } else {
                // 标识适用范围：标识文本须明示申请年龄范围在该档内的上下限（整数月）
                String lower = String.valueOf(band.coveredLower(ageLower));
                String upper = String.valueOf(band.coveredUpper(ageUpper));
                if (!label.contains(lower) || !label.contains(upper)) {
                    passed = false;
                    problems.add("分龄档 " + band.bandCode() + " 适用年龄标识未明示申请年龄范围"
                            + "在该档内的部分 " + seg + ": " + label);
                }
            }
        }

        String bandDesc = applicable.isEmpty() ? "无（年龄区间非法时不评档）"
                : applicable.stream().map(AgeBandRule::bandCode).toList().toString();
        add(details, reasons, now, filingNo, versionNo, "CHILD_BAND_MATERIAL", passed,
                "分龄档材料齐备性（每档独立安全报告+适用年龄标识，报告须明确覆盖申请年龄范围；跨档须两档都齐）：涉及档="
                        + bandDesc + "，" + (passed ? "各档材料齐备" : String.join("；", problems)));
    }

    private void evaluateReportIndependence(List<ChildBandDossier> dossiers,
                                            List<ReviewDetail> details,
                                            List<String> reasons,
                                            Instant now,
                                            String filingNo,
                                            int versionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChildBandDossier d : dossiers == null ? List.<ChildBandDossier>of() : dossiers) {
            if (d.report() == null) {
                continue;
            }
            String reportNo = d.report().reportNo();
            if (!seen.add(reportNo)) {
                passed = false;
                problems.add("同一报告编号被多个分龄档复用，各档须独立安全报告: " + reportNo);
            }
            if (reportPort.findByNo(reportNo).isPresent()) {
                passed = false;
                problems.add("报告编号 " + reportNo + " 为成人功效评价试验报告，"
                        + "成人功效报告不能替代儿童安全报告");
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_REPORT_INDEPENDENT", passed,
                "儿童安全报告独立性（成人功效报告不能替代儿童安全报告，各档报告独立）："
                        + (passed ? "报告独立有效" : String.join("；", problems)));
    }

    private void evaluateConcentration(List<FormulationIngredient> formulation,
                                       List<AgeBandRule> applicable,
                                       List<ReviewDetail> details,
                                       List<String> reasons,
                                       Instant now,
                                       String filingNo,
                                       int versionNo) {
        boolean passed = true;
        List<String> lines = new ArrayList<>();
        for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
            Optional<RestrictedIngredient> limit = catalogPort.findRestricted(ing.code());
            if (limit.isEmpty()) {
                continue;
            }
            BigDecimal strictest = strictestLimit(limit.get(), applicable);
            if (strictest == null) {
                continue;
            }
            int cmp = ing.concentrationPct().compareTo(strictest);
            boolean ok = cmp <= 0;
            lines.add(String.format("限用成分 %s 实测 %s%%，分龄较严限值 %s%%（涉及档=%s），恰等合法：%s",
                    ing.code(), ing.concentrationPct().stripTrailingZeros().toPlainString(),
                    strictest.stripTrailingZeros().toPlainString(),
                    applicable.stream().map(AgeBandRule::bandCode).toList(),
                    ok ? "通过" : "超限"));
            if (!ok) {
                passed = false;
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_RESTRICTED_CONCENTRATION", passed,
                "儿童限用成分浓度校验（按涉及分龄档限值较严者判定，恰等限值合法）："
                        + (lines.isEmpty() ? "配方无限用成分" : String.join("；", lines)));
    }

    /** 涉及分龄档中的较严（最小）限值；档未设儿童限值时回退成人限值。 */
    private BigDecimal strictestLimit(RestrictedIngredient limit, List<AgeBandRule> applicable) {
        BigDecimal strictest = null;
        for (AgeBandRule band : applicable) {
            BigDecimal bandLimit = switch (band.bandCode()) {
                case "BAND_0_36" -> limit.childMaxBand036Pct();
                case "BAND_37_144" -> limit.childMaxBand37144Pct();
                default -> null;
            };
            if (bandLimit == null) {
                bandLimit = limit.maxConcentrationPct();
            }
            if (strictest == null || bandLimit.compareTo(strictest) < 0) {
                strictest = bandLimit;
            }
        }
        return strictest;
    }

    private void add(List<ReviewDetail> details,
                     List<String> reasons,
                     Instant now,
                     String filingNo,
                     int versionNo,
                     String ruleCode,
                     boolean passed,
                     String detail) {
        details.add(new ReviewDetail(null, filingNo, versionNo, STAGE_FORM, ruleCode, passed, detail, now));
        if (!passed) {
            reasons.add("[" + ruleCode + "] " + detail);
        }
    }
}
