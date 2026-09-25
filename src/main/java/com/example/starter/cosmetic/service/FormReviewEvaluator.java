package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.RestrictedIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.CatalogPort;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 形式审查规则计算器（无外部状态，可单测复算）。
 *
 * <p>规则口径：</p>
 * <ol>
 *   <li>DOC_COMPLETE：备案资料齐全（产品名、配方、宣称均非空）。</li>
 *   <li>CLAIM_CATALOG：宣称功效须在合成目录内；不支持的功效宣称拒绝。</li>
 *   <li>SPECIAL_REPORT_PRESENT：特殊功效（防晒、美白、祛斑等）须有对应功效评价试验报告。</li>
 *   <li>REPORT_VALIDITY：报告须为备案提交日前 3 年内出具（UTC 日界），恰满 3 年当日有效；
 *       且出具日不得晚于提交日。</li>
 *   <li>CLAIM_REPORT_CONSISTENCY：宣称与报告结论须一致（报告结论支持对应功效）。</li>
 *   <li>RESTRICTED_CONCENTRATION：配方含限用成分的浓度不得超过限值，恰等限值合法。</li>
 * </ol>
 */
public class FormReviewEvaluator {

    /** 规则阶段标识。 */
    public static final String STAGE_FORM = "FORM";

    /** 报告有效期年数。 */
    public static final int REPORT_VALID_YEARS = 3;

    private final CatalogPort catalogPort;

    public FormReviewEvaluator(CatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    /**
     * 形式审查结果。
     *
     * @param passed  全部规则是否通过
     * @param details 逐条审查明细（含通过/不通过，可复算）
     * @param reasons 不通过原因汇总
     */
    public record Result(boolean passed, List<ReviewDetail> details, List<String> reasons) {
    }

    /**
     * 执行形式审查。
     *
     * @param productName 产品名称
     * @param formulation 配方快照
     * @param claims      功效宣称编码
     * @param reports     随附试验报告快照
     * @param submitDate  备案提交日（UTC 日界）
     * @param nowUtc      明细记录时刻(UTC)
     * @param filingNo    备案编号（驳回件亦分配编号）
     * @param versionNo   版本号
     */
    public Result evaluate(String productName,
                           List<FormulationIngredient> formulation,
                           List<String> claims,
                           List<TestReportRecord> reports,
                           LocalDate submitDate,
                           java.time.Instant nowUtc,
                           String filingNo,
                           int versionNo) {
        List<ReviewDetail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        evaluateDocuments(productName, formulation, claims, details, reasons, nowUtc, filingNo, versionNo);
        Map<String, EfficacyCatalog> claimCatalogs =
                evaluateCatalog(claims, details, reasons, nowUtc, filingNo, versionNo);
        Set<String> claimedSpecials =
                evaluateSpecialReports(claims, reports, claimCatalogs, details, reasons, nowUtc, filingNo, versionNo);
        Map<String, TestReportRecord> specialReports =
                evaluateReportValidityAndConsistency(claims, reports, claimCatalogs, claimedSpecials, submitDate,
                        details, reasons, nowUtc, filingNo, versionNo);
        evaluateConcentration(formulation, details, reasons, nowUtc, filingNo, versionNo);

        return new Result(reasons.isEmpty(), List.copyOf(details), List.copyOf(reasons));
    }

    private void evaluateDocuments(String productName,
                                   List<FormulationIngredient> formulation,
                                   List<String> claims,
                                   List<ReviewDetail> details,
                                   List<String> reasons,
                                   java.time.Instant now,
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

    private Map<String, EfficacyCatalog> evaluateCatalog(List<String> claims,
                                                         List<ReviewDetail> details,
                                                         List<String> reasons,
                                                         java.time.Instant now,
                                                         String filingNo,
                                                         int versionNo) {
        Map<String, EfficacyCatalog> resolved = new HashMap<>();
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            Optional<EfficacyCatalog> cat = catalogPort.findEfficacy(code);
            if (cat.isEmpty()) {
                passed = false;
                problems.add("功效编码不在合成目录: " + code);
            } else {
                EfficacyCatalog c = cat.get();
                resolved.put(code, c);
                if (!c.supported()) {
                    passed = false;
                    problems.add("不支持的功效宣称，拒绝: " + code + "(" + c.name() + ")");
                }
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CLAIM_CATALOG", passed,
                "功效宣称目录校验：" + (passed ? "全部宣称在支持目录内" : String.join("；", problems)));
        return resolved;
    }

    private Set<String> evaluateSpecialReports(List<String> claims,
                                               List<TestReportRecord> reports,
                                               Map<String, EfficacyCatalog> claimCatalogs,
                                               List<ReviewDetail> details,
                                               List<String> reasons,
                                               java.time.Instant now,
                                               String filingNo,
                                               int versionNo) {
        Set<String> specials = new HashSet<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            EfficacyCatalog cat = claimCatalogs.get(code);
            if (cat != null && cat.special()) {
                specials.add(code);
            }
        }
        Map<String, List<TestReportRecord>> byEfficacy = groupByEfficacy(reports);
        boolean passed = true;
        List<String> problems = new ArrayList<>();
        for (String code : specials) {
            if (byEfficacy.getOrDefault(code, List.of()).isEmpty()) {
                passed = false;
                problems.add("特殊功效缺少对应功效评价试验报告: " + code);
            }
        }
        add(details, reasons, now, filingNo, versionNo, "SPECIAL_REPORT_PRESENT", passed,
                "特殊功效报告齐备性：特殊功效=" + specials + "，"
                        + (passed ? "均有对应报告" : String.join("；", problems)));
        return specials;
    }

    private Map<String, TestReportRecord> evaluateReportValidityAndConsistency(
            List<String> claims,
            List<TestReportRecord> reports,
            Map<String, EfficacyCatalog> claimCatalogs,
            Set<String> claimedSpecials,
            LocalDate submitDate,
            List<ReviewDetail> details,
            List<String> reasons,
            java.time.Instant now,
            String filingNo,
            int versionNo) {
        LocalDate earliest = submitDate.minusYears(REPORT_VALID_YEARS);
        boolean validityPassed = true;
        boolean consistencyPassed = true;
        List<String> validityProblems = new ArrayList<>();
        List<String> consistencyProblems = new ArrayList<>();
        Map<String, TestReportRecord> specialReportChosen = new HashMap<>();

        // 仅校验对应当前被宣称特殊功效的报告；其余报告不参与本次宣称判定。
        for (TestReportRecord r : reports == null ? List.<TestReportRecord>of() : reports) {
            if (!claimedSpecials.contains(r.efficacyCode())) {
                continue;
            }
            // 有效期：earliest <= issuedDate <= submitDate；恰满 3 年当日（issuedDate == earliest）有效
            boolean dateOk = !r.issuedDate().isBefore(earliest) && !r.issuedDate().isAfter(submitDate);
            long ageYears = ChronoUnit.YEARS.between(r.issuedDate(), submitDate);
            String validityDetail = String.format(
                    "报告 %s（功效 %s）出具日 %s，提交日 %s，3年口径下界 %s（含当日），年限=%d → %s",
                    r.reportNo(), r.efficacyCode(), r.issuedDate(), submitDate, earliest, ageYears,
                    dateOk ? "在有效期内" : "超出3年有效期或出具日晚于提交日");
            if (!dateOk) {
                validityPassed = false;
                validityProblems.add(validityDetail);
            }
            // 一致性：报告结论必须支持其对应宣称
            if (!r.conclusionSupported()) {
                consistencyPassed = false;
                consistencyProblems.add(String.format(
                        "报告 %s（功效 %s）结论为不支持，与宣称不一致", r.reportNo(), r.efficacyCode()));
            } else {
                // 同一功效多份报告时，记录其中一份支持的有效报告
                specialReportChosen.putIfAbsent(r.efficacyCode(), r);
            }
        }

        add(details, reasons, now, filingNo, versionNo, "REPORT_VALIDITY", validityPassed,
                "报告3年有效期（UTC 日界，恰满3年当日有效）："
                        + (validityPassed
                        ? (claimedSpecials.isEmpty() ? "无特殊功效，免报告" : "全部对应报告在有效期内")
                        : String.join("；", validityProblems)));
        add(details, reasons, now, filingNo, versionNo, "CLAIM_REPORT_CONSISTENCY", consistencyPassed,
                "宣称与报告结论一致性："
                        + (consistencyPassed
                        ? (claimedSpecials.isEmpty() ? "无特殊功效宣称" : "宣称均与报告结论一致")
                        : String.join("；", consistencyProblems)));
        return specialReportChosen;
    }

    private void evaluateConcentration(List<FormulationIngredient> formulation,
                                       List<ReviewDetail> details,
                                       List<String> reasons,
                                       java.time.Instant now,
                                       String filingNo,
                                       int versionNo) {
        boolean passed = true;
        List<String> lines = new ArrayList<>();
        for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
            Optional<RestrictedIngredient> limit = catalogPort.findRestricted(ing.code());
            if (limit.isEmpty()) {
                continue;
            }
            int cmp = ing.concentrationPct().compareTo(limit.get().maxConcentrationPct());
            boolean ok = cmp <= 0;
            lines.add(String.format("限用成分 %s 实测 %s%%，限值 %s%%，恰等合法：%s",
                    ing.code(), ing.concentrationPct().stripTrailingZeros().toPlainString(),
                    limit.get().maxConcentrationPct().stripTrailingZeros().toPlainString(),
                    ok ? "通过" : "超限"));
            if (!ok) {
                passed = false;
            }
        }
        add(details, reasons, now, filingNo, versionNo, "RESTRICTED_CONCENTRATION", passed,
                "限用成分浓度校验（恰等限值合法）：" + (lines.isEmpty() ? "配方无限用成分" : String.join("；", lines)));
    }

    private Map<String, List<TestReportRecord>> groupByEfficacy(List<TestReportRecord> reports) {
        Map<String, List<TestReportRecord>> map = new HashMap<>();
        for (TestReportRecord r : reports == null ? List.<TestReportRecord>of() : reports) {
            map.computeIfAbsent(r.efficacyCode(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    private void add(List<ReviewDetail> details,
                     List<String> reasons,
                     java.time.Instant now,
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
