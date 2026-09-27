package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildReviewDetail;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 儿童分龄形式审查规则计算器（无外部状态，可单测复算）。
 *
 * <p>规则口径（任一缺项一次列全，零生效）：</p>
 * <ol>
 *   <li>AGE_RANGE_VALID：整数月闭区间满足 0≤下限≤上限≤144。</li>
 *   <li>REQUIRED_TIERS：按区间命中分档；跨档（下限≤36 且上限≥37）须两档资料齐全。</li>
 *   <li>CHILD_CLAIM_CATALOG：只允许合成儿童功效目录内且 supported 的功效。</li>
 *   <li>TIER_SAFETY_REPORT_PRESENT：每个命中分档须有独立的儿童安全报告（成人功效报告不能替代）。</li>
 *   <li>TIER_REPORT_COVERAGE：每档报告必须明确覆盖该档内申请年龄段（整段闭区间）。</li>
 *   <li>TIER_REPORT_CONCLUSION：报告结论须支持该档儿童使用。</li>
 *   <li>AGE_LABEL：适用年龄标识文本须明确标注与申请一致的年龄上下限（整数月）。</li>
 *   <li>CHILD_CONCENTRATION：命中分档均列限的成分取两档较严（最小）限值，恰等限值合格。</li>
 * </ol>
 */
public class ChildAgeReviewEvaluator {

    /** 儿童规则阶段标识。 */
    public static final String STAGE_CHILD_FORM = "CHILD_FORM";

    /** 年龄上限（整数月）。 */
    public static final int MAX_AGE_MONTHS = 144;

    private final ChildCatalogPort catalogPort;

    public ChildAgeReviewEvaluator(ChildCatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    /**
     * 审查结果。
     *
     * @param passed        全部规则是否通过
     * @param details       逐条审查明细
     * @param reasons       不通过原因汇总（一次列全）
     * @param requiredTiers 命中的分档（按 0~36、37~144 顺序）
     */
    public record Result(boolean passed,
                         List<ChildReviewDetail> details,
                         List<String> reasons,
                         List<AgeTier> requiredTiers) {
    }

    /**
     * 执行分龄形式审查。
     *
     * @param productName 产品名称
     * @param fromMonths  申请年龄下限
     * @param toMonths    申请年龄上限
     * @param formulation 配方
     * @param claims      儿童功效宣称
     * @param reports     分档独立安全报告
     * @param ageLabelText 适用年龄标识文本
     * @param ruleVersion 受理冻结的分龄规则版本
     * @param nowUtc      明细记录时刻(UTC)
     * @param filingNo    备案编号
     * @param versionNo   修订版本号
     */
    public Result evaluate(String productName,
                           int fromMonths,
                           int toMonths,
                           List<FormulationIngredient> formulation,
                           List<String> claims,
                           List<ChildSafetyReport> reports,
                           String ageLabelText,
                           String ruleVersion,
                           Instant nowUtc,
                           String filingNo,
                           int versionNo) {
        List<ChildReviewDetail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        boolean ageValid = evaluateAgeRange(productName, fromMonths, toMonths, details, reasons,
                nowUtc, filingNo, versionNo);

        List<AgeTier> requiredTiers = ageValid
                ? determineTiers(fromMonths, toMonths)
                : List.of();
        evaluateRequiredTiers(requiredTiers, fromMonths, toMonths, details, nowUtc, filingNo, versionNo);

        evaluateClaims(claims, details, reasons, nowUtc, filingNo, versionNo);

        if (ageValid) {
            evaluateTierReports(requiredTiers, fromMonths, toMonths, reports, details, reasons,
                    nowUtc, filingNo, versionNo);
            evaluateAgeLabel(ageLabelText, fromMonths, toMonths, details, reasons,
                    nowUtc, filingNo, versionNo);
            evaluateConcentration(requiredTiers, formulation, ruleVersion, details, reasons,
                    nowUtc, filingNo, versionNo);
        } else {
            // 年龄区间非法时其余分龄规则无法定位，仍给出占位结论，全部不通过并在原因中列明
            add(details, reasons, nowUtc, filingNo, versionNo, "TIER_SAFETY_REPORT_PRESENT", false,
                    "年龄区间非法，无法判定分档安全报告齐备性");
            add(details, reasons, nowUtc, filingNo, versionNo, "AGE_LABEL", false,
                    "年龄区间非法，无法核对适用年龄标识");
            add(details, reasons, nowUtc, filingNo, versionNo, "CHILD_CONCENTRATION", false,
                    "年龄区间非法，无法按分档判定浓度限值");
        }

        return new Result(reasons.isEmpty(), List.copyOf(details), List.copyOf(reasons), requiredTiers);
    }

    /** 计算命中分档（有序去重）。 */
    public static List<AgeTier> determineTiers(int from, int to) {
        Set<AgeTier> tiers = new LinkedHashSet<>();
        for (AgeTier tier : AgeTier.values()) {
            if (tier.covers(from, to)) {
                tiers.add(tier);
            }
        }
        return List.copyOf(tiers);
    }

    /** 分档在申请区间内覆盖的年龄段（闭区间）。 */
    public static int[] tierSlice(AgeTier tier, int from, int to) {
        int lo = Math.max(from, tier.fromMonths());
        int hi = Math.min(to, tier.toMonths());
        return new int[]{lo, hi};
    }

    private boolean evaluateAgeRange(String productName,
                                     int from,
                                     int to,
                                     List<ChildReviewDetail> details,
                                     List<String> reasons,
                                     Instant now,
                                     String filingNo,
                                     int versionNo) {
        boolean nameOk = productName != null && !productName.isBlank();
        boolean rangeOk = from >= 0 && to <= MAX_AGE_MONTHS && from <= to;
        boolean passed = nameOk && rangeOk;
        String detail = String.format(
                "儿童资料与年龄区间：产品名称=%s；申请年龄闭区间=[%d,%d]整数月，约束 0≤下限≤上限≤%d → %s",
                nameOk ? "齐全" : "缺失", from, to, MAX_AGE_MONTHS,
                rangeOk ? "区间合法" : "区间非法");
        add(details, reasons, now, filingNo, versionNo, "AGE_RANGE_VALID", passed, detail);
        return passed;
    }

    private void evaluateRequiredTiers(List<AgeTier> tiers,
                                       int from,
                                       int to,
                                       List<ChildReviewDetail> details,
                                       Instant now,
                                       String filingNo,
                                       int versionNo) {
        List<String> codes = tiers.stream().map(Enum::name).toList();
        boolean crossTier = tiers.size() == 2;
        String detail = String.format(
                "分档命中：申请区间[%d,%d] → 命中分档=%s；%s",
                from, to, codes,
                crossTier ? "跨档，须 0~36 与 37~144 两档资料都齐" : "单档，须该档资料齐全");
        details.add(new ChildReviewDetail(null, filingNo, versionNo,
                "REQUIRED_TIERS", true, detail, now));
    }

    private void evaluateClaims(List<String> claims,
                                List<ChildReviewDetail> details,
                                List<String> reasons,
                                Instant now,
                                String filingNo,
                                int versionNo) {
        boolean passed = claims != null && !claims.isEmpty();
        List<String> problems = new ArrayList<>();
        for (String code : claims == null ? List.<String>of() : claims) {
            Optional<ChildEfficacy> cat = catalogPort.findEfficacy(code);
            if (cat.isEmpty()) {
                passed = false;
                problems.add("功效编码不在儿童合成目录: " + code);
            } else if (!cat.get().supported()) {
                passed = false;
                problems.add("不允许的儿童功效宣称: " + code + "(" + cat.get().name() + ")");
            }
        }
        if (claims == null || claims.isEmpty()) {
            problems.add("儿童功效宣称不能为空");
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_CLAIM_CATALOG", passed,
                "儿童功效目录校验：" + (passed ? "全部宣称在儿童支持目录内" : String.join("；", problems)));
    }

    private void evaluateTierReports(List<AgeTier> requiredTiers,
                                     int from,
                                     int to,
                                     List<ChildSafetyReport> reports,
                                     List<ChildReviewDetail> details,
                                     List<String> reasons,
                                     Instant now,
                                     String filingNo,
                                     int versionNo) {
        Map<AgeTier, List<ChildSafetyReport>> byTier = new EnumMap<>(AgeTier.class);
        for (AgeTier t : AgeTier.values()) {
            byTier.put(t, new ArrayList<>());
        }
        for (ChildSafetyReport r : reports == null ? List.<ChildSafetyReport>of() : reports) {
            byTier.computeIfAbsent(r.tier(), k -> new ArrayList<>()).add(r);
        }

        for (AgeTier tier : requiredTiers) {
            List<ChildSafetyReport> tierReports = byTier.getOrDefault(tier, List.of());

            // 规则1：该档独立安全报告是否齐备（成人功效报告结构性不能替代）
            boolean present = !tierReports.isEmpty();
            add(details, reasons, now, filingNo, versionNo, "TIER_SAFETY_REPORT_PRESENT", present,
                    String.format("分档 %s 独立儿童安全报告齐备性：%s（成人功效报告不能替代儿童安全报告）",
                            tier.name(), present ? "已提供 " + tierReports.size() + " 份" : "缺少该档独立安全报告"));

            int[] slice = tierSlice(tier, from, to);
            int sliceFrom = slice[0];
            int sliceTo = slice[1];

            boolean coverage = false;
            boolean conclusion = false;
            List<String> coverageLines = new ArrayList<>();
            List<String> conclusionLines = new ArrayList<>();
            for (ChildSafetyReport r : tierReports) {
                boolean covers = r.covers(sliceFrom, sliceTo);
                coverageLines.add(String.format(
                        "报告 %s 声明覆盖[%d,%d]，该档申请段[%d,%d] → %s",
                        r.reportNo(), r.coversFromMonths(), r.coversToMonths(),
                        sliceFrom, sliceTo, covers ? "覆盖" : "未完整覆盖"));
                if (covers) {
                    coverage = true;
                }
                if (r.conclusionSupported()) {
                    conclusion = true;
                } else {
                    conclusionLines.add("报告 " + r.reportNo() + " 结论不支持该档儿童使用");
                }
            }
            if (tierReports.isEmpty()) {
                coverageLines.add(String.format("无 %s 档报告，无法覆盖申请段[%d,%d]",
                        tier.name(), sliceFrom, sliceTo));
                conclusionLines.add("无 " + tier.name() + " 档报告");
            } else if (conclusion) {
                conclusionLines.clear();
            }

            add(details, reasons, now, filingNo, versionNo, "TIER_REPORT_COVERAGE", coverage,
                    "分档 " + tier.name() + " 报告覆盖申请年龄范围：" + String.join("；", coverageLines));
            add(details, reasons, now, filingNo, versionNo, "TIER_REPORT_CONCLUSION",
                    conclusion && present,
                    "分档 " + tier.name() + " 报告结论一致性："
                            + (conclusion ? "存在支持该档儿童使用的安全报告" : String.join("；", conclusionLines)));
        }

        // 提供了不属于任一命中分档的报告（例如拿成人/他档材料充数）也一次性指出
        List<String> stray = new ArrayList<>();
        Set<AgeTier> required = new LinkedHashSet<>(requiredTiers);
        for (ChildSafetyReport r : reports == null ? List.<ChildSafetyReport>of() : reports) {
            if (!required.contains(r.tier())) {
                stray.add("报告 " + r.reportNo() + " 对应分档 " + r.tier()
                        + " 不在本申请命中分档内，不能替代所需分档安全报告");
            }
        }
        if (!stray.isEmpty()) {
            add(details, reasons, now, filingNo, versionNo, "TIER_REPORT_SCOPE", false,
                    "分档报告适用范围核对：" + String.join("；", stray));
        }
    }

    private void evaluateAgeLabel(String label,
                                  int from,
                                  int to,
                                  List<ChildReviewDetail> details,
                                  List<String> reasons,
                                  Instant now,
                                  String filingNo,
                                  int versionNo) {
        boolean nonBlank = label != null && !label.isBlank();
        boolean mentionsFrom = nonBlank && label.contains(String.valueOf(from));
        boolean mentionsTo = nonBlank && label.contains(String.valueOf(to));
        boolean mentionsMonth = nonBlank && (label.contains("月") || label.contains("岁"));
        boolean passed = mentionsFrom && mentionsTo && mentionsMonth;
        String detail = String.format(
                "适用年龄标识：文本=%s；须明确标注适用年龄上下限 %d、%d（整数月）并含月龄/岁表述 → %s",
                nonBlank ? "「" + label + "」" : "缺失", from, to,
                passed ? "标识与申请年龄一致" : "标识未明确标注与申请一致的适用年龄范围");
        add(details, reasons, now, filingNo, versionNo, "AGE_LABEL", passed, detail);
    }

    private void evaluateConcentration(List<AgeTier> requiredTiers,
                                       List<FormulationIngredient> formulation,
                                       String ruleVersion,
                                       List<ChildReviewDetail> details,
                                       List<String> reasons,
                                       Instant now,
                                       String filingNo,
                                       int versionNo) {
        // 收集命中分档涉及的全部限用成分
        Set<String> ingredientCodes = new LinkedHashSet<>();
        Map<AgeTier, Map<String, BigDecimal>> limitsByTier = new EnumMap<>(AgeTier.class);
        for (AgeTier tier : requiredTiers) {
            Map<String, BigDecimal> map = new HashMap<>();
            limitsByTier.put(tier, map);
            for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
                catalogPort.findLimit(ruleVersion, tier, ing.code()).ifPresent(limit -> {
                    map.put(ing.code(), limit);
                    ingredientCodes.add(ing.code());
                });
            }
        }

        boolean passed = true;
        List<String> lines = new ArrayList<>();
        for (FormulationIngredient ing : formulation == null ? List.<FormulationIngredient>of() : formulation) {
            if (!ingredientCodes.contains(ing.code())) {
                continue;
            }
            // 跨档取两档较严（最小）限值；单档取该档限值
            BigDecimal strictest = null;
            List<String> perTier = new ArrayList<>();
            for (AgeTier tier : requiredTiers) {
                BigDecimal limit = limitsByTier.getOrDefault(tier, Map.of()).get(ing.code());
                if (limit != null) {
                    perTier.add(tier.name() + "=" + limit.stripTrailingZeros().toPlainString() + "%");
                    if (strictest == null || limit.compareTo(strictest) < 0) {
                        strictest = limit;
                    }
                }
            }
            if (strictest == null) {
                continue;
            }
            boolean ok = ing.concentrationPct().compareTo(strictest) <= 0;
            lines.add(String.format("成分 %s 实测 %s%%，各档限值[%s]，较严限值 %s%%，恰等合格：%s",
                    ing.code(), ing.concentrationPct().stripTrailingZeros().toPlainString(),
                    String.join(",", perTier), strictest.stripTrailingZeros().toPlainString(),
                    ok ? "通过" : "超限"));
            if (!ok) {
                passed = false;
            }
        }
        add(details, reasons, now, filingNo, versionNo, "CHILD_CONCENTRATION", passed,
                "儿童分档浓度校验（跨档取较严，恰等限值合格）："
                        + (lines.isEmpty() ? "配方在命中分档无限用成分" : String.join("；", lines)));
    }

    private void add(List<ChildReviewDetail> details,
                     List<String> reasons,
                     Instant now,
                     String filingNo,
                     int versionNo,
                     String ruleCode,
                     boolean passed,
                     String detail) {
        details.add(new ChildReviewDetail(null, filingNo, versionNo,
                ruleCode, passed, detail, now));
        if (!passed) {
            reasons.add("[" + ruleCode + "] " + detail);
        }
    }
}
