package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChildrenAgeBandRule;
import com.example.starter.cosmetic.domain.ChildrenBandLimit;
import com.example.starter.cosmetic.domain.ChildrenEfficacy;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.port.ChildrenCatalogPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 儿童化妆品分龄资料审查器（无外部状态，可单测复算）。
 *
 * <p>规则口径（任一缺项一次列全并零生效）：</p>
 * <ol>
 *   <li>AGE_RANGE_VALID：申请年龄范围为整数月闭区间，0≤下限≤上限≤144。</li>
 *   <li>DOC_COMPLETE：资料齐全（产品名、配方、宣称均非空）。</li>
 *   <li>CHILD_CLAIM_CATALOG：宣称须为合成目录中允许的儿童功效。</li>
 *   <li>BAND_SAFETY_REPORT：每个适用档须有独立儿童安全报告（成人功效报告不能替代）。</li>
 *   <li>SAFETY_REPORT_COVERAGE：安全报告须明确覆盖申请年龄范围（按档交集判定）。</li>
 *   <li>SAFETY_REPORT_CONCLUSION：安全报告结论须为支持。</li>
 *   <li>BAND_AGE_LABEL：每个适用档须有适用年龄标识。</li>
 *   <li>AGE_LABEL_COVERAGE：标识适用范围须覆盖申请年龄范围（按档交集判定）。</li>
 *   <li>CHILD_CONCENTRATION：限用成分浓度按适用各档限值较严者判定，恰等限值合格。</li>
 * </ol>
 */
public class ChildrenReviewEvaluator {

    /** 规则阶段标识。 */
    public static final String STAGE_FORM = "FORM";

    /** 申请年龄范围合法上限（整数月）。 */
    public static final int MAX_AGE_MONTHS = 144;

    private final ChildrenCatalogPort catalogPort;

    public ChildrenReviewEvaluator(ChildrenCatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    /**
     * 审查结果。
     *
     * @param passed  全部规则是否通过
     * @param details 逐条审查明细（含通过/不通过，可复算）
     * @param reasons 不通过原因汇总（一次列全）
     */
    public record Result(boolean passed, List<ReviewDetail> details, List<String> reasons) {
    }

    /**
     * 执行儿童分龄资料审查。
     *
     * @param productName       产品名称
     * @param ageLowerMonths    申请年龄下限（整数月，闭区间）
     * @param ageUpperMonths    申请年龄上限（整数月，闭区间）
     * @param formulation       配方快照
     * @param claims            儿童功效宣称编码
     * @param safetyReports     儿童安全报告清单
     * @param labels            适用年龄标识清单
     * @param adultReportCount  随附成人功效评价报告数量（仅用于说明不能替代，不参与判定）
     * @param nowUtc            明细记录时刻(UTC)
     * @param filingNo          备案编号
     * @param versionNo         版本号
     */
    public Result evaluate(String productName,
                           int ageLowerMonths,
                           int ageUpperMonths,
                           List<FormulationIngredient> formulation,
                           List<String> claims,
                           List<ChildrenSafetyReport> safetyReports,
                           List<AgeLabel> labels,
                           int adultReportCount,
                           Instant nowUtc,
                           String filingNo,
                           int versionNo) {
        List<ReviewDetail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        boolean ageValid = evaluateAgeRange(ageLowerMonths, ageUpperMonths, details, reasons,
                nowUtc, filingNo, versionNo);
        evaluateDocuments(productName, formulation, claims, details, reasons, nowUtc, filingNo, versionNo);
        evaluateClaims(claims, details, reasons, nowUtc, filingNo, versionNo);

        List<ChildrenAgeBandRule> bands = ageValid
                ? applicableBands(ageLowerMonths, ageUpperMonths)
                : List.of();
        evaluateSafetyReports(bands, ageLowerMonths, ageUpperMonths, safetyReports, adultReportCount,
                details, reasons, nowUtc, filingNo, versionNo);
        evaluateLabels(bands, ageLowerMonths, ageUpperMonths, labels,
                details, reasons, nowUtc, filingNo, versionNo);
        evaluateConcentration(bands, formulation, details, reasons, nowUtc, filingNo, versionNo);

        return new Result(reasons.isEmpty(), List.copyOf(details), List.copyOf(reasons));
    }

    /** 计算申请年龄范围覆盖的适用档位（闭区间交集非空）。 */
    public List<ChildrenAgeBandRule> applicableBands(int ageLowerMonths, int ageUpperMonths) {
        List<ChildrenAgeBandRule> applicable = new ArrayList<>();
        for (ChildrenAgeBandRule band : catalogPort.listBandRules()) {
            if (ageLowerMonths <= band.upperMonths() && ageUpperMonths >= band.lowerMonths()) {
                applicable.add(band);
            }
        }
        return applicable;
    }

    private boolean evaluateAgeRange(int lower, int upper,
                                     List<ReviewDetail> details, List<String> reasons,
                                     Instant now, String filingNo, int versionNo) {
        boolean passed = lower >= 0 && lower <= upper && upper <= MAX_AGE_MONTHS;
        String detail = String.format(
                "申请年龄范围（整数月闭区间）：[%d, %d]，合法口径 0≤下限≤上限≤%d → %s",
                lower, upper, MAX_AGE_MONTHS, passed ? "合法" : "非法");
        add(details, reasons, now, filingNo, versionNo, "AGE_RANGE_VALID", passed, detail);
        return passed;
    }

    private void evaluateDocuments(String productName,
                                   List<FormulationIngredient> formulation,
                                   List<String> claims,
                                   List<ReviewDetail> details, List<String> reasons,
                                   Instant now, String filingNo, int versionNo) {
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

    private void evaluateClaims(List<String> claims,
                                List<ReviewDetail> details, List<String> reasons,
                                Instant now, String filingNo, int versionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            Optional<ChildrenEfficacy> cat = catalogPort.findChildrenEfficacy(code);
            if (cat.isEmpty()) {
                passed = false;
                problems.add("功效编码不在儿童合成目录: " + code);
            } else if (!cat.get().supported()) {
                passed = false;
                problems.add("儿童不允许的功效宣称，拒绝: " + code + "(" + cat.get().name() + ")");
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_CLAIM_CATALOG", passed,
                "儿童功效目录校验：" + (passed ? "全部宣称在儿童允许目录内" : String.join("；", problems)));
    }

    private void evaluateSafetyReports(List<ChildrenAgeBandRule> bands,
                                       int ageLower, int ageUpper,
                                       List<ChildrenSafetyReport> safetyReports,
                                       int adultReportCount,
                                       List<ReviewDetail> details, List<String> reasons,
                                       Instant now, String filingNo, int versionNo) {
        List<ChildrenSafetyReport> reports = safetyReports == null ? List.of() : safetyReports;
        boolean presentPassed = true;
        boolean coveragePassed = true;
        boolean conclusionPassed = true;
        List<String> presentProblems = new ArrayList<>();
        List<String> coverageProblems = new ArrayList<>();
        List<String> conclusionProblems = new ArrayList<>();

        for (ChildrenAgeBandRule band : bands) {
            int segLower = Math.max(ageLower, band.lowerMonths());
            int segUpper = Math.min(ageUpper, band.upperMonths());
            List<ChildrenSafetyReport> bandReports = reports.stream()
                    .filter(r -> r.bandCode().equals(band.bandCode()))
                    .toList();
            if (bandReports.isEmpty()) {
                presentPassed = false;
                String problem = String.format("年龄档 %s(%d~%d月) 缺少独立儿童安全报告",
                        band.bandCode(), band.lowerMonths(), band.upperMonths());
                if (adultReportCount > 0) {
                    problem += "（随附成人功效评价报告 " + adultReportCount + " 份，不能替代儿童安全报告）";
                }
                presentProblems.add(problem);
                continue;
            }
            boolean covered = bandReports.stream().anyMatch(r ->
                    r.coverageLowerMonths() <= segLower && r.coverageUpperMonths() >= segUpper);
            if (!covered) {
                coveragePassed = false;
                coverageProblems.add(String.format(
                        "年龄档 %s 安全报告未明确覆盖申请年龄范围 [%d, %d] 月：已登记覆盖区间=%s",
                        band.bandCode(), segLower, segUpper, coveragesOf(bandReports)));
            }
            boolean allSupported = bandReports.stream().allMatch(ChildrenSafetyReport::conclusionSupported);
            if (!allSupported) {
                conclusionPassed = false;
                conclusionProblems.add(String.format(
                        "年龄档 %s 存在结论不支持的儿童安全报告: %s",
                        band.bandCode(),
                        bandReports.stream().filter(r -> !r.conclusionSupported())
                                .map(ChildrenSafetyReport::reportNo).toList()));
            }
        }

        add(details, reasons, now, filingNo, versionNo, "BAND_SAFETY_REPORT", presentPassed,
                "分档独立儿童安全报告齐备性（成人功效报告不能替代）："
                        + (presentPassed ? "各适用档均有独立安全报告" : String.join("；", presentProblems)));
        add(details, reasons, now, filingNo, versionNo, "SAFETY_REPORT_COVERAGE", coveragePassed,
                "安全报告年龄覆盖（须明确覆盖申请年龄范围）："
                        + (coveragePassed ? "各档报告覆盖申请年龄范围" : String.join("；", coverageProblems)));
        add(details, reasons, now, filingNo, versionNo, "SAFETY_REPORT_CONCLUSION", conclusionPassed,
                "安全报告结论一致性："
                        + (conclusionPassed ? "各档报告结论均为支持" : String.join("；", conclusionProblems)));
    }

    private void evaluateLabels(List<ChildrenAgeBandRule> bands,
                                int ageLower, int ageUpper,
                                List<AgeLabel> labels,
                                List<ReviewDetail> details, List<String> reasons,
                                Instant now, String filingNo, int versionNo) {
        List<AgeLabel> labelList = labels == null ? List.of() : labels;
        boolean presentPassed = true;
        boolean coveragePassed = true;
        List<String> presentProblems = new ArrayList<>();
        List<String> coverageProblems = new ArrayList<>();

        for (ChildrenAgeBandRule band : bands) {
            int segLower = Math.max(ageLower, band.lowerMonths());
            int segUpper = Math.min(ageUpper, band.upperMonths());
            List<AgeLabel> bandLabels = labelList.stream()
                    .filter(l -> l.bandCode().equals(band.bandCode()))
                    .toList();
            if (bandLabels.isEmpty()) {
                presentPassed = false;
                presentProblems.add(String.format("年龄档 %s(%d~%d月) 缺少适用年龄标识",
                        band.bandCode(), band.lowerMonths(), band.upperMonths()));
                continue;
            }
            boolean covered = bandLabels.stream().anyMatch(l ->
                    l.applicableLowerMonths() <= segLower && l.applicableUpperMonths() >= segUpper);
            if (!covered) {
                coveragePassed = false;
                coverageProblems.add(String.format(
                        "年龄档 %s 适用年龄标识未覆盖申请年龄范围 [%d, %d] 月：标识适用范围=%s",
                        band.bandCode(), segLower, segUpper, labelRangesOf(bandLabels)));
            }
        }

        add(details, reasons, now, filingNo, versionNo, "BAND_AGE_LABEL", presentPassed,
                "分档适用年龄标识齐备性："
                        + (presentPassed ? "各适用档均有适用年龄标识" : String.join("；", presentProblems)));
        add(details, reasons, now, filingNo, versionNo, "AGE_LABEL_COVERAGE", coveragePassed,
                "标识适用范围覆盖（须覆盖申请年龄范围）："
                        + (coveragePassed ? "各档标识覆盖申请年龄范围" : String.join("；", coverageProblems)));
    }

    private void evaluateConcentration(List<ChildrenAgeBandRule> bands,
                                       List<FormulationIngredient> formulation,
                                       List<ReviewDetail> details, List<String> reasons,
                                       Instant now, String filingNo, int versionNo) {
        boolean passed = true;
        List<String> lines = new ArrayList<>();
        for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
            // 适用各档限值取较严者（最小值）
            BigDecimal strictest = null;
            for (ChildrenAgeBandRule band : bands) {
                Optional<ChildrenBandLimit> limit =
                        catalogPort.findBandLimit(band.bandCode(), ing.code());
                if (limit.isPresent()
                        && (strictest == null
                        || limit.get().maxConcentrationPct().compareTo(strictest) < 0)) {
                    strictest = limit.get().maxConcentrationPct();
                }
            }
            if (strictest == null) {
                continue;
            }
            boolean ok = ing.concentrationPct().compareTo(strictest) <= 0;
            lines.add(String.format("限用成分 %s 实测 %s%%，适用各档较严限值 %s%%，恰等合法：%s",
                    ing.code(),
                    ing.concentrationPct().stripTrailingZeros().toPlainString(),
                    strictest.stripTrailingZeros().toPlainString(),
                    ok ? "通过" : "超限"));
            if (!ok) {
                passed = false;
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_CONCENTRATION", passed,
                "儿童限用成分浓度校验（按适用各档限值较严者判定，恰等限值合格）："
                        + (lines.isEmpty() ? "配方无儿童限用成分" : String.join("；", lines)));
    }

    private static String coveragesOf(List<ChildrenSafetyReport> reports) {
        return reports.stream()
                .map(r -> r.reportNo() + "[" + r.coverageLowerMonths() + "," + r.coverageUpperMonths() + "]")
                .toList().toString();
    }

    private static String labelRangesOf(List<AgeLabel> labels) {
        return labels.stream()
                .map(l -> "[" + l.applicableLowerMonths() + "," + l.applicableUpperMonths() + "]")
                .toList().toString();
    }

    private void add(List<ReviewDetail> details, List<String> reasons,
                     Instant now, String filingNo, int versionNo,
                     String ruleCode, boolean passed, String detail) {
        details.add(new ReviewDetail(null, filingNo, versionNo, STAGE_FORM, ruleCode, passed, detail, now));
        if (!passed) {
            reasons.add("[" + ruleCode + "] " + detail);
        }
    }
}
