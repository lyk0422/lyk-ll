package com.example.starter.cosmetic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.starter.cosmetic.api.IngredientPayload;
import com.example.starter.cosmetic.api.ReportPayload;
import com.example.starter.cosmetic.domain.EfficacyEntry;
import com.example.starter.cosmetic.domain.RestrictedEntry;
import com.example.starter.cosmetic.domain.ReviewItem;
import org.junit.jupiter.api.Test;

/**
 * 形式审查规则的纯单元测试：报告3年恰满、宣称一致性、不支持宣称、限用浓度恰等。
 */
class FormalReviewServiceTest {

    private static final LocalDate SUBMIT_DATE = LocalDate.of(2026, 1, 1);

    private final FormalReviewService service = new FormalReviewService(null);

    private final Map<String, EfficacyEntry> catalog = Map.of(
            "SUNSCREEN", new EfficacyEntry("SUNSCREEN", "防晒", true, true),
            "WHITENING", new EfficacyEntry("WHITENING", "美白", true, true),
            "MOISTURIZING", new EfficacyEntry("MOISTURIZING", "保湿", false, true),
            "HAIR_GROWTH", new EfficacyEntry("HAIR_GROWTH", "生发", false, false));

    private final Map<String, RestrictedEntry> restricted = Map.of(
            "SALICYLIC_ACID", new RestrictedEntry("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0")));

    private List<ReviewItem> review(List<String> claims, List<IngredientPayload> ingredients,
                                    List<ReportPayload> reports) {
        return service.review(SUBMIT_DATE, "合成包装文字", claims, ingredients, reports, catalog, restricted);
    }

    private static ReportPayload report(String efficacyCode, String conclusion, LocalDate issuedDate) {
        return new ReportPayload(efficacyCode, conclusion, issuedDate, "合成检验机构");
    }

    @Test
    void normalCosmeticWithCompleteMaterialsPasses() {
        List<ReviewItem> items = review(List.of("MOISTURIZING"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("5.0"))), List.of());
        assertThat(items).allMatch(ReviewItem::passed);
    }

    @Test
    void incompleteMaterialsFail() {
        List<ReviewItem> items = service.review(SUBMIT_DATE, " ", List.of("MOISTURIZING"),
                List.of(), List.of(), catalog, restricted);
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("资料完整性"));
    }

    @Test
    void reportExactlyThreeYearsOldIsValid() {
        // 恰满3年当日有效：提交日2026-01-01，报告出具日2023-01-01
        List<ReviewItem> items = review(List.of("SUNSCREEN"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("5.0"))),
                List.of(report("SUNSCREEN", "SUPPORT", LocalDate.of(2023, 1, 1))));
        assertThat(items).filteredOn(item -> item.item().equals("报告有效期"))
                .allMatch(ReviewItem::passed);
        assertThat(items).allMatch(ReviewItem::passed);
    }

    @Test
    void reportOneDayBeyondThreeYearsIsInvalid() {
        // 2022-12-31 出具，早于提交日前3年（2023-01-01）一天，超出有效期
        List<ReviewItem> items = review(List.of("SUNSCREEN"), List.of(),
                List.of(report("SUNSCREEN", "SUPPORT", LocalDate.of(2022, 12, 31))));
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("报告有效期"));
    }

    @Test
    void reportIssuedAfterSubmitDateIsInvalid() {
        List<ReviewItem> items = review(List.of("SUNSCREEN"), List.of(),
                List.of(report("SUNSCREEN", "SUPPORT", LocalDate.of(2026, 1, 2))));
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("报告有效期"));
    }

    @Test
    void specialClaimWithoutReportFails() {
        List<ReviewItem> items = review(List.of("WHITENING"), List.of(), List.of());
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("功效评价报告"));
    }

    @Test
    void claimInconsistentWithReportConclusionFails() {
        List<ReviewItem> items = review(List.of("WHITENING"), List.of(),
                List.of(report("WHITENING", "NOT_SUPPORT", LocalDate.of(2025, 6, 1))));
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("宣称与报告一致性"));
    }

    @Test
    void reportForDifferentEfficacyDoesNotCoverClaim() {
        List<ReviewItem> items = review(List.of("WHITENING"), List.of(),
                List.of(report("SUNSCREEN", "SUPPORT", LocalDate.of(2025, 6, 1))));
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("功效评价报告"));
    }

    @Test
    void unsupportedClaimIsRejected() {
        List<ReviewItem> items = review(List.of("HAIR_GROWTH"), List.of(), List.of());
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("功效宣称支持性"));
    }

    @Test
    void unknownClaimIsRejected() {
        List<ReviewItem> items = review(List.of("UNKNOWN_CLAIM"), List.of(), List.of());
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("功效宣称支持性"));
    }

    @Test
    void concentrationEqualToLimitIsLegal() {
        // 恰等限值合法：水杨酸限值2.0%，浓度恰为2.0%
        List<ReviewItem> items = review(List.of("MOISTURIZING"),
                List.of(new IngredientPayload("SALICYLIC_ACID", new BigDecimal("2.0"))), List.of());
        assertThat(items).filteredOn(item -> item.item().equals("限用成分浓度"))
                .allMatch(ReviewItem::passed);
        assertThat(items).allMatch(ReviewItem::passed);
    }

    @Test
    void concentrationOverLimitFails() {
        List<ReviewItem> items = review(List.of("MOISTURIZING"),
                List.of(new IngredientPayload("SALICYLIC_ACID", new BigDecimal("2.1"))), List.of());
        assertThat(items).anyMatch(item -> !item.passed() && item.item().equals("限用成分浓度"));
    }

    @Test
    void unrestrictedIngredientIsNotLimited() {
        List<ReviewItem> items = review(List.of("MOISTURIZING"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("99.0"))), List.of());
        assertThat(items).allMatch(ReviewItem::passed);
    }
}
