package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.AgeBand;
import com.example.starter.cosmetic.child.domain.ChildAgeRule;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildLabel;
import com.example.starter.cosmetic.child.domain.ChildRestrictedLimit;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 儿童分龄资料审查计算器（无外部状态，可单测复算）。
 *
 * <p>规则口径（任一缺项一次列全并零生效）：</p>
 * <ol>
 *   <li>CHILD_CLAIM_CATALOG：宣称须为儿童合成目录内且支持的功效；成人功效编码不在儿童目录。</li>
 *   <li>CHILD_SAFETY_REPORT_PRESENT：每个必需档位须有独立儿童安全报告；
 *       成人功效评价报告不能替代儿童安全报告。</li>
 *   <li>CHILD_LABEL_PRESENT：每个必需档位须有适用年龄标识。</li>
 *   <li>CHILD_REPORT_COVERAGE：每档安全报告须明确覆盖申请年龄范围落在该档的区间。</li>
 *   <li>CHILD_CONCENTRATION：限用成分按必需档位中较严（较低）限值判定，恰等限值合格。</li>
 * </ol>
 */
public class ChildReviewEvaluator {

    /** 儿童审查阶段标识。 */
    public static final String STAGE_CHILD_REVIEW = "CHILD_REVIEW";

    private final ChildCatalogPort catalogPort;

    public ChildReviewEvaluator(ChildCatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    /**
     * 审查结果。
     *
     * @param passed  全部规则是否通过
     * @param details 逐条判定证据（含通过/不通过，可复算）
     * @param reasons 不通过原因汇总（缺项一次列全）
     */
    public record Result(boolean passed, List<ReviewDetail> details, List<String> reasons) {
    }

    /**
     * 执行儿童分龄资料审查。
     *
     * @param ageLowerMonths 申请年龄下限（整数月，含）
     * @param ageUpperMonths 申请年龄上限（整数月，含）
     * @param claims         儿童功效宣称编码
     * @param formulation    配方快照
     * @param safetyReports  分档儿童安全报告
     * @param labels         分档适用年龄标识
     * @param rule           当前分龄规则版本（受理时随修订冻结）
     * @param nowUtc         证据记录时刻(UTC)
     * @param filingNo       儿童备案编号
     * @param revisionNo     修订号
     */
    public Result evaluate(int ageLowerMonths,
                           int ageUpperMonths,
                           List<String> claims,
                           List<FormulationIngredient> formulation,
                           List<ChildSafetyReport> safetyReports,
                           List<ChildLabel> labels,
                           ChildAgeRule rule,
                           Instant nowUtc,
                           String filingNo,
                           int revisionNo) {
        List<ReviewDetail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<AgeBand> requiredBands = rule.requiredBands(ageLowerMonths, ageUpperMonths);

        evaluateClaims(claims, details, reasons, nowUtc, filingNo, revisionNo);
        evaluateReportPresence(requiredBands, safetyReports, details, reasons, nowUtc, filingNo, revisionNo);
        evaluateLabelPresence(requiredBands, labels, details, reasons, nowUtc, filingNo, revisionNo);
        evaluateReportCoverage(requiredBands, ageLowerMonths, ageUpperMonths, safetyReports,
                details, reasons, nowUtc, filingNo, revisionNo);
        evaluateConcentration(requiredBands, formulation, rule, details, reasons, nowUtc, filingNo, revisionNo);

        return new Result(reasons.isEmpty(), List.copyOf(details), List.copyOf(reasons));
    }

    private void evaluateClaims(List<String> claims,
                                List<ReviewDetail> details,
                                List<String> reasons,
                                Instant now,
                                String filingNo,
                                int revisionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            Optional<ChildEfficacy> efficacy = catalogPort.findEfficacy(code);
            if (efficacy.isEmpty()) {
                passed = false;
                problems.add("功效编码不在儿童合成目录（成人功效不能作为儿童宣称）: " + code);
            } else if (!efficacy.get().supported()) {
                passed = false;
                problems.add("儿童目录不支持的功效宣称，拒绝: " + code + "(" + efficacy.get().name() + ")");
            }
        }
        add(details, reasons, now, filingNo, revisionNo, "CHILD_CLAIM_CATALOG", passed,
                "儿童功效目录校验：" + (passed ? "全部宣称在儿童合成目录内且支持" : String.join("；", problems)));
    }

    private void evaluateReportPresence(List<AgeBand> requiredBands,
                                        List<ChildSafetyReport> safetyReports,
                                        List<ReviewDetail> details,
                                        List<String> reasons,
                                        Instant now,
                                        String filingNo,
                                        int revisionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (AgeBand band : requiredBands) {
            boolean has = reportsOfBand(safetyReports, band.bandCode()).findAny().isPresent();
            if (!has) {
                passed = false;
                problems.add(String.format("档位 %s（%d~%d月）缺少独立儿童安全报告（成人功效报告不能替代）",
                        band.bandCode(), band.lowerMonths(), band.upperMonths()));
            }
        }
        add(details, reasons, now, filingNo, revisionNo, "CHILD_SAFETY_REPORT_PRESENT", passed,
                "分档儿童安全报告齐备性（每档独立，成人功效报告不能替代）："
                        + (passed ? "各必需档位均有安全报告" : String.join("；", problems)));
    }

    private void evaluateLabelPresence(List<AgeBand> requiredBands,
                                       List<ChildLabel> labels,
                                       List<ReviewDetail> details,
                                       List<String> reasons,
                                       Instant now,
                                       String filingNo,
                                       int revisionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (AgeBand band : requiredBands) {
            boolean has = (labels == null ? List.<ChildLabel>of() : labels).stream()
                    .anyMatch(l -> band.bandCode().equals(l.bandCode()));
            if (!has) {
                passed = false;
                problems.add(String.format("档位 %s（%d~%d月）缺少适用年龄标识",
                        band.bandCode(), band.lowerMonths(), band.upperMonths()));
            }
        }
        add(details, reasons, now, filingNo, revisionNo, "CHILD_LABEL_PRESENT", passed,
                "分档适用年龄标识齐备性：" + (passed ? "各必需档位均有标识" : String.join("；", problems)));
    }

    private void evaluateReportCoverage(List<AgeBand> requiredBands,
                                        int ageLowerMonths,
                                        int ageUpperMonths,
                                        List<ChildSafetyReport> safetyReports,
                                        List<ReviewDetail> details,
                                        List<String> reasons,
                                        Instant now,
                                        String filingNo,
                                        int revisionNo) {
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (AgeBand band : requiredBands) {
            int segLower = band.segmentLower(ageLowerMonths);
            int segUpper = band.segmentUpper(ageUpperMonths);
            boolean covered = reportsOfBand(safetyReports, band.bandCode())
                    .anyMatch(r -> r.coverLowerMonths() <= segLower && r.coverUpperMonths() >= segUpper);
            if (!covered) {
                passed = false;
                problems.add(String.format(
                        "档位 %s 安全报告未明确覆盖申请年龄范围该档区间 [%d,%d] 月",
                        band.bandCode(), segLower, segUpper));
            }
        }
        add(details, reasons, now, filingNo, revisionNo, "CHILD_REPORT_COVERAGE", passed,
                "安全报告年龄覆盖校验（报告须明确覆盖申请年龄范围）："
                        + (passed ? "各档报告均覆盖申请年龄范围" : String.join("；", problems)));
    }

    private void evaluateConcentration(List<AgeBand> requiredBands,
                                       List<FormulationIngredient> formulation,
                                       ChildAgeRule rule,
                                       List<ReviewDetail> details,
                                       List<String> reasons,
                                       Instant now,
                                       String filingNo,
                                       int revisionNo) {
        Map<String, List<ChildRestrictedLimit>> limitsByIngredient =
                catalogPort.listRestrictedLimits(rule.ruleVersion()).stream()
                        .collect(Collectors.groupingBy(ChildRestrictedLimit::ingredientCode));
        boolean passed = true;
        List<String> lines = new ArrayList<>();
        for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
            List<ChildRestrictedLimit> limits = limitsByIngredient.get(ing.code());
            if (limits == null) {
                continue;
            }
            // 跨档按必需档位中较严（较低）限值判定
            BigDecimal strictest = null;
            for (AgeBand band : requiredBands) {
                for (ChildRestrictedLimit limit : limits) {
                    if (band.bandCode().equals(limit.bandCode())
                            && (strictest == null
                            || limit.maxConcentrationPct().compareTo(strictest) < 0)) {
                        strictest = limit.maxConcentrationPct();
                    }
                }
            }
            if (strictest == null) {
                continue;
            }
            boolean ok = ing.concentrationPct().compareTo(strictest) <= 0;
            lines.add(String.format("限用成分 %s 实测 %s%%，分龄较严限值 %s%%（恰等合格）：%s",
                    ing.code(), ing.concentrationPct().stripTrailingZeros().toPlainString(),
                    strictest.stripTrailingZeros().toPlainString(), ok ? "通过" : "超限"));
            if (!ok) {
                passed = false;
            }
        }
        add(details, reasons, now, filingNo, revisionNo, "CHILD_CONCENTRATION", passed,
                "儿童限用成分浓度校验（跨档取较严限值，恰等合格）："
                        + (lines.isEmpty() ? "配方无儿童限用成分" : String.join("；", lines)));
    }

    private java.util.stream.Stream<ChildSafetyReport> reportsOfBand(List<ChildSafetyReport> reports,
                                                                     String bandCode) {
        return (reports == null ? List.<ChildSafetyReport>of() : reports).stream()
                .filter(r -> bandCode.equals(r.bandCode()));
    }

    private void add(List<ReviewDetail> details,
                     List<String> reasons,
                     Instant now,
                     String filingNo,
                     int revisionNo,
                     String ruleCode,
                     boolean passed,
                     String detail) {
        details.add(new ReviewDetail(null, filingNo, revisionNo, STAGE_CHILD_REVIEW,
                ruleCode, passed, detail, now));
        if (!passed) {
            reasons.add("[" + ruleCode + "] " + detail);
        }
    }
}
