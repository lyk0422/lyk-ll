package com.example.starter.cosmetic.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.example.starter.cosmetic.api.IngredientPayload;
import com.example.starter.cosmetic.api.ReportPayload;
import com.example.starter.cosmetic.domain.EfficacyEntry;
import com.example.starter.cosmetic.domain.RestrictedEntry;
import com.example.starter.cosmetic.domain.ReviewItem;
import com.example.starter.cosmetic.repo.CatalogRepository;
import org.springframework.stereotype.Service;

/**
 * 形式审查：资料完整性、功效宣称支持性、特殊功效报告（3年有效期，UTC日界，恰满3年当日有效）、
 * 宣称与报告结论一致性、限用成分浓度（恰等限值合法）。纯规则计算，结果落库为审查明细。
 */
@Service
public class FormalReviewService {

    /** 特殊功效报告有效期年数。 */
    static final int REPORT_VALIDITY_YEARS = 3;

    private final CatalogRepository catalogRepository;

    public FormalReviewService(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    /**
     * 执行形式审查，返回逐项审查结果（全部通过方为通过）。
     *
     * @param submitDate 备案提交日（UTC 日界）
     */
    public List<ReviewItem> review(LocalDate submitDate, String packagingText, List<String> claims,
                                   List<IngredientPayload> ingredients, List<ReportPayload> reports) {
        return review(submitDate, packagingText, claims, ingredients, reports,
                catalogRepository.findAllEfficacy(), catalogRepository.findAllRestricted());
    }

    /**
     * 使用给定目录执行形式审查（便于纯单元测试）。
     */
    List<ReviewItem> review(LocalDate submitDate, String packagingText, List<String> claims,
                            List<IngredientPayload> ingredients, List<ReportPayload> reports,
                            Map<String, EfficacyEntry> catalog, Map<String, RestrictedEntry> restricted) {
        List<ReviewItem> items = new ArrayList<>();

        boolean complete = packagingText != null && !packagingText.isBlank()
                && ingredients != null && !ingredients.isEmpty();
        items.add(new ReviewItem("资料完整性", complete,
                complete ? "备案资料齐全（包装文字、配方成分完整）" : "备案资料不齐全：包装文字与配方成分必填"));

        for (String code : claims == null ? List.<String>of() : new TreeSet<>(claims)) {
            reviewClaim(submitDate, code, catalog.get(code), reports, items);
        }

        for (IngredientPayload ingredient : ingredients == null ? List.<IngredientPayload>of() : ingredients) {
            RestrictedEntry entry = restricted.get(ingredient.ingredientCode());
            if (entry == null) {
                continue;
            }
            boolean withinLimit = ingredient.concentration().compareTo(entry.maxConcentration()) <= 0;
            items.add(new ReviewItem("限用成分浓度", withinLimit,
                    "成分 %s(%s) 浓度 %s%%，限值 %s%%：%s".formatted(
                            ingredient.ingredientCode(), entry.name(),
                            ingredient.concentration().stripTrailingZeros().toPlainString(),
                            entry.maxConcentration().stripTrailingZeros().toPlainString(),
                            withinLimit ? "未超过限值（恰等限值合法）" : "超过限值")));
        }
        return items;
    }

    private void reviewClaim(LocalDate submitDate, String code, EfficacyEntry entry,
                             List<ReportPayload> reports, List<ReviewItem> items) {
        if (entry == null || !entry.supported()) {
            items.add(new ReviewItem("功效宣称支持性", false,
                    "不支持的功效宣称：%s（不在目录或目录标记为不支持）".formatted(code)));
            return;
        }
        items.add(new ReviewItem("功效宣称支持性", true,
                "功效宣称受支持：%s(%s)%s".formatted(code, entry.name(), entry.special() ? "，特殊功效" : "")));
        if (!entry.special()) {
            return;
        }
        List<ReportPayload> matched = reports == null ? List.<ReportPayload>of() : reports.stream()
                .filter(report -> code.equals(report.efficacyCode()))
                .toList();
        if (matched.isEmpty()) {
            items.add(new ReviewItem("功效评价报告", false,
                    "特殊功效 %s 须上传对应功效评价试验报告，当前未上传".formatted(code)));
            return;
        }
        boolean anySupport = matched.stream().anyMatch(report -> "SUPPORT".equals(report.conclusion()));
        if (!anySupport) {
            items.add(new ReviewItem("宣称与报告一致性", false,
                    "功效 %s 的报告结论与宣称不一致（无 SUPPORT 结论报告）".formatted(code)));
            return;
        }
        LocalDate earliest = submitDate.minusYears(REPORT_VALIDITY_YEARS);
        boolean anyValid = matched.stream()
                .filter(report -> "SUPPORT".equals(report.conclusion()))
                .anyMatch(report -> !report.issuedDate().isAfter(submitDate) && !report.issuedDate().isBefore(earliest));
        if (anyValid) {
            items.add(new ReviewItem("报告有效期", true,
                    "功效 %s 的报告出具日在备案提交日 %s 前 %d 年内（UTC日界，恰满%d年当日 %s 有效）".formatted(
                            code, submitDate, REPORT_VALIDITY_YEARS, REPORT_VALIDITY_YEARS, earliest)));
        } else {
            items.add(new ReviewItem("报告有效期", false,
                    "功效 %s 无有效报告：须为备案提交日 %s 前 %d 年内出具（最早 %s，恰满当日有效）且不得晚于提交日".formatted(
                            code, submitDate, REPORT_VALIDITY_YEARS, earliest)));
        }
    }
}
