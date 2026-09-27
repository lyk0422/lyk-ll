package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.InquiryRequests.BidLineInput;
import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.InquiryRequests.InquiryItemSpec;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.dto.InquiryViews.BidView;
import com.example.starter.damage.dto.InquiryViews.InquiryView;
import com.example.starter.damage.dto.InquiryViews.OpeningEntryView;
import com.example.starter.damage.dto.InquiryViews.OpeningView;
import com.example.starter.damage.dto.InquiryViews.ReviewResultView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.InquiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密封询价业务流测试：真实 H2（MODE=MySQL）验证固定残损项/工法、UTC 截止边界、
 * 整版报价拒绝、开标时刻资格判定、最低总价/同价编号排序、双重复核转批复、
 * 重复开标冻结、批复后撤销风险事件、expectedVersion 与 requestId 幂等、旧入口兼容。
 */
@SpringBootTest
@Import(InquiryTestClockConfig.class)
class InquiryFlowServiceTest {

    private static final Instant T0 = InquiryTestClockConfig.BASE;
    private static final Instant DEADLINE = T0.plus(Duration.ofDays(1));
    private static final Instant AFTER_DEADLINE = DEADLINE.plus(Duration.ofMinutes(1));

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private DamageCaseService caseService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from bid_line");
        jdbc.update("delete from bid_version");
        jdbc.update("delete from bid_review");
        jdbc.update("delete from bid_opening_entry");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from bid");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from supplier_method");
        jdbc.update("delete from supplier");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        clock.set(T0);
    }

    // ---- 构造辅助 ----

    private long repairCaseWithTwoItems(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("500.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        return caseId;
    }

    private List<Long> itemIds(long caseId) {
        return caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
    }

    private long publish(long caseId, String requestId, int expectedVersion) {
        List<Long> items = itemIds(caseId);
        return inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                requestId, expectedVersion, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "STRAIGHTEN")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE"))))).id();
    }

    private void supplier(String no, List<String> methods) {
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-" + UUID.randomUUID(), no, "供应商-" + no, methods));
        inquiryService.grantQualification(no, new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
    }

    private BidView bid(long inquiryId, String supplierNo, int expectedVersion,
                        String method1, long cents1, String method2, long cents2, String requestId) {
        return inquiryService.submitBid(inquiryId, supplierNo, new SubmitBidRequest(
                requestId, expectedVersion, List.of(
                        new BidLineInput(1, method1, cents1),
                        new BidLineInput(2, method2, cents2))));
    }

    private OpeningView open(long inquiryId, int expectedVersion, String requestId) {
        return inquiryService.openBids(inquiryId, new OpenBidsRequest(requestId, expectedVersion));
    }

    // ---- 主流程 ----

    @Test
    void sealedBiddingLowestTotalWinsAndTwoReviewersConvertToApproval() {
        long caseId = repairCaseWithTwoItems("TEMU3000001");
        long inquiryId = publish(caseId, "pub-1", 0);
        supplier("S001", List.of("WELD"));
        supplier("S002", List.of("STRAIGHTEN", "REPLACE"));

        InquiryView inquiry = inquiryService.getInquiry(inquiryId);
        assertThat(inquiry.version()).isEqualTo(1);
        assertThat(inquiry.status().name()).isEqualTo("OPEN");
        assertThat(inquiry.items()).hasSize(2);
        assertThat(inquiry.items().get(0).allowedMethods()).containsExactly("STRAIGHTEN", "WELD");

        bid(inquiryId, "S001", 0, "WELD", 1000, "WELD", 2000, "bid-s1-1"); // 3000
        bid(inquiryId, "S002", 0, "STRAIGHTEN", 1500, "REPLACE", 1400, "bid-s2-1"); // 2900

        clock.set(AFTER_DEADLINE);
        OpeningView opening = open(inquiryId, 1, "open-1");

        assertThat(opening.status()).isEqualTo("AWARDED");
        assertThat(opening.winnerSupplierNo()).isEqualTo("S002");
        assertThat(opening.winnerTotalCents()).isEqualTo(2900);
        assertThat(opening.entries()).hasSize(2);
        OpeningEntryView s1 = opening.entries().stream().filter(e -> e.supplierNo().equals("S001")).findFirst().orElseThrow();
        OpeningEntryView s2 = opening.entries().stream().filter(e -> e.supplierNo().equals("S002")).findFirst().orElseThrow();
        assertThat(s1.valid()).isTrue();
        assertThat(s1.rankNo()).isEqualTo(2);
        assertThat(s2.valid()).isTrue();
        assertThat(s2.rankNo()).isEqualTo(1);
        assertThat(s2.qualificationVersion()).isEqualTo(1);

        // 开标后询价版本 +1，报价冻结
        assertThat(inquiryService.getInquiry(inquiryId).version()).isEqualTo(2);
        assertThat(inquiryService.getInquiry(inquiryId).status().name()).isEqualTo("OPENED");

        // 第一名复核不转批复
        ReviewResultView first = inquiryService.review(inquiryId, new ReviewRequest("rv-" + UUID.randomUUID(), "alice"));
        assertThat(first.opening().converted()).isFalse();
        assertThat(first.approvalDate()).isNull();
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.ESTIMATED);

        // 第二名不同复核人齐 → 转批复
        ReviewResultView second = inquiryService.review(inquiryId, new ReviewRequest("rv-" + UUID.randomUUID(), "bob"));
        assertThat(second.opening().converted()).isTrue();
        assertThat(second.approvalDate()).isEqualTo(java.time.LocalDate.parse("2026-09-21"));

        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.caseInfo().determination()).isEqualTo(Determination.REPAIR);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).source()).isEqualTo("INQUIRY");
        assertThat(detail.approvals().get(0).openingId()).isEqualTo(opening.id());
        assertThat(inquiryService.getInquiry(inquiryId).status().name()).isEqualTo("CONVERTED");
        assertThat(inquiryService.getInquiry(inquiryId).version()).isEqualTo(3);
        assertThat(inquiryService.getBid(inquiryId, "S002").status()).isEqualTo("FROZEN");
    }

    // ---- 截止边界 ----

    @Test
    void bidBeforeDeadlineAllowedExactlyAtDeadlineRejected() {
        long caseId = repairCaseWithTwoItems("TEMU3000002");
        long inquiryId = publish(caseId, "pub-2", 0);
        supplier("S100", List.of("WELD"));

        // now == 截止时刻：拒绝
        clock.set(DEADLINE);
        assertThatThrownBy(() -> bid(inquiryId, "S100", 0, "WELD", 100, "WELD", 200, "bid-edge"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("截止");

        // 截止前一刻：允许
        clock.set(DEADLINE.minus(Duration.ofNanos(1)));
        BidView view = bid(inquiryId, "S100", 0, "WELD", 100, "WELD", 200, "bid-edge-ok");
        assertThat(view.currentVersion()).isEqualTo(1);
    }

    @Test
    void openBeforeDeadlineRejected() {
        long caseId = repairCaseWithTwoItems("TEMU3000003");
        long inquiryId = publish(caseId, "pub-3", 0);
        supplier("S101", List.of("WELD"));
        bid(inquiryId, "S101", 0, "WELD", 100, "WELD", 200, "b1");
        // 默认 T0 仍早于截止
        assertThatThrownBy(() -> open(inquiryId, 1, "open-early"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ---- 整版报价拒绝 ----

    @Test
    void bidMustCoverAllLinesOtherwiseWholeVersionRejected() {
        long caseId = repairCaseWithTwoItems("TEMU3000004");
        long inquiryId = publish(caseId, "pub-4", 0);
        supplier("S102", List.of("WELD"));

        // 只报一行：整版拒绝，不产生任何报价主表/版本
        assertThatThrownBy(() -> inquiryService.submitBid(inquiryId, "S102", new SubmitBidRequest(
                "bad-cover", 0, List.of(new BidLineInput(1, "WELD", 100L)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        Integer bidCount = jdbc.queryForObject("select count(*) from bid where inquiry_id = ?", Integer.class, inquiryId);
        assertThat(bidCount).isZero();

        // 行号越界：整版拒绝
        assertThatThrownBy(() -> inquiryService.submitBid(inquiryId, "S102", new SubmitBidRequest(
                "bad-line", 0, List.of(
                        new BidLineInput(1, "WELD", 100L),
                        new BidLineInput(9, "WELD", 200L)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void nonPositiveAmountOrDisallowedMethodRejectsWholeBid() {
        long caseId = repairCaseWithTwoItems("TEMU3000005");
        long inquiryId = publish(caseId, "pub-5", 0);
        supplier("S103", List.of("WELD"));

        // 金额非正：400
        assertThatThrownBy(() -> bid(inquiryId, "S103", 0, "WELD", 0, "WELD", 200, "bad-amount"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 工法不在询价允许集合：422 整版拒绝
        assertThatThrownBy(() -> bid(inquiryId, "S103", 0, "WELD", 100, "REPLACE", 200, "bad-method-inquiry"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 工法不在供应商适用集合（PATCH 供应商不会）：422
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-p", "S104", "仅补漆", List.of("PAINT")));
        inquiryService.grantQualification("S104", new QualificationGrantRequest(
                "q-p", 0, T0.minus(Duration.ofDays(1)), null));
        assertThatThrownBy(() -> bid(inquiryId, "S104", 0, "WELD", 100, "WELD", 200, "bad-method-supplier"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ---- 开标时刻资格判定 ----

    @Test
    void qualificationRevokedBeforeOpeningExcludesBidUsingOpeningTime() {
        long caseId = repairCaseWithTwoItems("TEMU3000006");
        long inquiryId = publish(caseId, "pub-6", 0);
        supplier("S200", List.of("WELD"));
        bid(inquiryId, "S200", 0, "WELD", 100, "WELD", 200, "b200"); // 提交时资格 ACTIVE（版本1）

        // 开标前撤销资格（追加版本2 REVOKED）
        clock.set(AFTER_DEADLINE.minusSeconds(30));
        inquiryService.revokeQualification("S200", new QualificationRevokeRequest(
                "rev-200", 1, AFTER_DEADLINE.minusSeconds(30)));

        OpeningView opening = open(inquiryId, 1, "open-200");
        assertThat(opening.status()).isEqualTo("NO_VALID");
        assertThat(opening.winnerSupplierId()).isNull();
        OpeningEntryView entry = opening.entries().get(0);
        assertThat(entry.valid()).isFalse();
        assertThat(entry.excludeReason()).isEqualTo("QUALIFICATION_REVOKED");
        assertThat(entry.rankNo()).isNull();
        assertThat(entry.qualificationVersion()).isEqualTo(2); // 按开标时刻最新资格，不采用提交时旧资格
        assertThat(inquiryService.getInquiry(inquiryId).status().name()).isEqualTo("NO_AWARD");
    }

    @Test
    void qualificationExpiredAtOpeningExcludesBid() {
        long caseId = repairCaseWithTwoItems("TEMU3000007");
        long inquiryId = publish(caseId, "pub-7", 0);
        inquiryService.createSupplier(new CreateSupplierRequest("csup-e", "S201", "短期资格", List.of("WELD")));
        // 资格在开标时刻之前到期
        inquiryService.grantQualification("S201", new QualificationGrantRequest(
                "q-e", 0, T0.minus(Duration.ofDays(10)), DEADLINE.minusSeconds(1)));
        bid(inquiryId, "S201", 0, "WELD", 100, "WELD", 200, "b201");

        clock.set(AFTER_DEADLINE);
        OpeningView opening = open(inquiryId, 1, "open-201");
        assertThat(opening.status()).isEqualTo("NO_VALID");
        assertThat(opening.entries().get(0).valid()).isFalse();
        assertThat(opening.entries().get(0).excludeReason()).isEqualTo("QUALIFICATION_EXPIRED");
    }

    @Test
    void qualificationValidExactlyAtOpeningBoundaryIsAccepted() {
        long caseId = repairCaseWithTwoItems("TEMU3000008");
        long inquiryId = publish(caseId, "pub-8", 0);
        inquiryService.createSupplier(new CreateSupplierRequest("csup-b", "S202", "边界资格", List.of("WELD")));
        // 区间 [validFrom, validTo)：validTo 恰为开标时刻，t < validTo 不成立 → 失效；
        // 这里令 validTo 恰为开标后 1 秒，验证开标时刻被覆盖
        inquiryService.grantQualification("S202", new QualificationGrantRequest(
                "q-b", 0, T0.minus(Duration.ofDays(10)), AFTER_DEADLINE.plusSeconds(1)));
        bid(inquiryId, "S202", 0, "WELD", 100, "WELD", 200, "b202");
        clock.set(AFTER_DEADLINE);
        OpeningView opening = open(inquiryId, 1, "open-202");
        assertThat(opening.status()).isEqualTo("AWARDED");
        assertThat(opening.entries().get(0).valid()).isTrue();
    }

    // ---- 同价排序与重复开标 ----

    @Test
    void equalTotalTieBreaksBySupplierNoAscending() {
        long caseId = repairCaseWithTwoItems("TEMU3000009");
        long inquiryId = publish(caseId, "pub-9", 0);
        supplier("S900", List.of("WELD"));
        supplier("S300", List.of("WELD")); // 编号字典序 S300 < S900
        bid(inquiryId, "S900", 0, "WELD", 100, "WELD", 200, "b900"); // 300
        bid(inquiryId, "S300", 0, "WELD", 150, "WELD", 150, "b300"); // 300

        clock.set(AFTER_DEADLINE);
        OpeningView opening = open(inquiryId, 1, "open-tie");
        assertThat(opening.status()).isEqualTo("AWARDED");
        assertThat(opening.winnerSupplierNo()).isEqualTo("S300");
        List<Integer> ranks = opening.entries().stream().filter(OpeningEntryView::valid)
                .map(OpeningEntryView::rankNo).sorted().toList();
        assertThat(ranks).containsExactly(1, 2);
    }

    @Test
    void repeatOpeningReturnsFrozenListIgnoringLaterQualificationChange() {
        long caseId = repairCaseWithTwoItems("TEMU3000010");
        long inquiryId = publish(caseId, "pub-10", 0);
        supplier("S400", List.of("WELD"));
        supplier("S401", List.of("STRAIGHTEN", "REPLACE"));
        bid(inquiryId, "S400", 0, "WELD", 500, "WELD", 500, "b400"); // 1000 高价
        bid(inquiryId, "S401", 0, "STRAIGHTEN", 100, "REPLACE", 100, "b401"); // 200 中标
        clock.set(AFTER_DEADLINE);
        OpeningView first = open(inquiryId, 1, "open-first");
        assertThat(first.winnerSupplierNo()).isEqualTo("S401");
        long firstOpeningId = first.id();

        // 开标后撤销中标供应商资格
        inquiryService.revokeQualification("S401", new QualificationRevokeRequest(
                "rev-401", 1, AFTER_DEADLINE.plusSeconds(10)));

        // 重复开标（不同 requestId 与 expectedVersion=2）返回首次冻结清单，不重算
        OpeningView again = inquiryService.openBids(inquiryId, new OpenBidsRequest("open-again", 2));
        assertThat(again.id()).isEqualTo(firstOpeningId);
        assertThat(again.winnerSupplierNo()).isEqualTo("S401");
        assertThat(again.entries()).hasSize(2);
        assertThat(again.entries()).filteredOn(e -> e.supplierNo().equals("S401"))
                .singleElement().satisfies(e -> {
                    assertThat(e.valid()).isTrue();
                    assertThat(e.rankNo()).isEqualTo(1);
                    assertThat(e.qualificationVersion()).isEqualTo(1); // 冻结的是开标时旧资格快照
                });
        assertThat(inquiryService.getInquiry(inquiryId).version()).isEqualTo(2);
    }

    // ---- 零有效报价与旧入口兼容 ----

    @Test
    void noValidBidsNoApprovalThenRepublishAndLegacyApprovalCompatible() {
        long caseId = repairCaseWithTwoItems("TEMU3000011");
        long inquiryId = publish(caseId, "pub-11", 0);
        supplier("S500", List.of("WELD"));
        bid(inquiryId, "S500", 0, "WELD", 100, "WELD", 200, "b500");
        clock.set(AFTER_DEADLINE);
        inquiryService.revokeQualification("S500", new QualificationRevokeRequest(
                "rev-500", 1, AFTER_DEADLINE.minusSeconds(1)));
        OpeningView opening = open(inquiryId, 1, "open-novalid");
        assertThat(opening.status()).isEqualTo("NO_VALID");

        // 零有效报价不允许复核开批复
        assertThatThrownBy(() -> inquiryService.review(inquiryId, new ReviewRequest("rv-x", "alice")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // NO_AWARD 后旧批复入口保持兼容（无询价转批复）
        caseService.approve(caseId, new ApprovalRequest("ap-legacy",
                java.time.LocalDate.parse("2026-09-21")));
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.approvals().get(0).source()).isEqualTo("DIRECT");
        assertThat(detail.approvals().get(0).openingId()).isNull();
    }

    // ---- 批复后撤销仅风险事件 ----

    @Test
    void revokeAfterConversionOnlyRecordsRiskEventAndKeepsWinnerEvidence() {
        long caseId = repairCaseWithTwoItems("TEMU3000012");
        long inquiryId = publish(caseId, "pub-12", 0);
        supplier("S600", List.of("WELD", "STRAIGHTEN", "REPLACE"));
        bid(inquiryId, "S600", 0, "WELD", 100, "REPLACE", 200, "b600");
        clock.set(AFTER_DEADLINE);
        OpeningView opening = open(inquiryId, 1, "open-600");
        inquiryService.review(inquiryId, new ReviewRequest("rv1", "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv2", "bob"));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);

        // 批复后撤销中标资格
        var revoked = inquiryService.revokeQualification("S600",
                new QualificationRevokeRequest("rev-600", 1, AFTER_DEADLINE.plusSeconds(60)));
        assertThat(revoked.currentQualification().status().name()).isEqualTo("REVOKED");
        assertThat(revoked.version()).isEqualTo(2);

        // 历史中标证据不改，案件状态/批复不变，仅追加风险事件
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).source()).isEqualTo("INQUIRY");
        assertThat(detail.events()).extracting(e -> e.eventType()).contains("QUALIFICATION_RISK");
        OpeningView frozen = inquiryService.getOpening(inquiryId);
        assertThat(frozen.winnerSupplierNo()).isEqualTo("S600");
        assertThat(frozen.entries()).filteredOn(e -> e.supplierNo().equals("S600"))
                .singleElement().satisfies(e -> {
                    assertThat(e.valid()).isTrue();
                    assertThat(e.qualificationVersion()).isEqualTo(1);
                });
    }

    // ---- 双重复核约束 ----

    @Test
    void sameReviewerTwiceRejectedAndSingleReviewerDoesNotConvert() {
        long caseId = repairCaseWithTwoItems("TEMU3000013");
        long inquiryId = publish(caseId, "pub-13", 0);
        supplier("S700", List.of("WELD", "STRAIGHTEN", "REPLACE"));
        bid(inquiryId, "S700", 0, "WELD", 100, "REPLACE", 200, "b700");
        clock.set(AFTER_DEADLINE);
        open(inquiryId, 1, "open-700");

        inquiryService.review(inquiryId, new ReviewRequest("rv-alice-1", "alice"));
        assertThatThrownBy(() -> inquiryService.review(inquiryId, new ReviewRequest("rv-alice-2", "alice")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.ESTIMATED);

        // 转批复后再复核拒绝
        inquiryService.review(inquiryId, new ReviewRequest("rv-bob", "bob"));
        assertThatThrownBy(() -> inquiryService.review(inquiryId, new ReviewRequest("rv-carol", "carol")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ---- expectedVersion 与修订历史 ----

    @Test
    void bidRevisionKeepsHistoryAndVersionConflictsReturn409() {
        long caseId = repairCaseWithTwoItems("TEMU3000014");
        long inquiryId = publish(caseId, "pub-14", 0);
        supplier("S800", List.of("WELD", "STRAIGHTEN", "REPLACE"));

        BidView v1 = bid(inquiryId, "S800", 0, "WELD", 100, "REPLACE", 100, "rev-1");
        assertThat(v1.currentVersion()).isEqualTo(1);
        assertThat(v1.history()).hasSize(1);

        // 错误期望版本
        assertThatThrownBy(() -> bid(inquiryId, "S800", 9, "WELD", 50, "REPLACE", 50, "rev-bad"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        BidView v2 = bid(inquiryId, "S800", 1, "STRAIGHTEN", 50, "REPLACE", 60, "rev-2");
        assertThat(v2.currentVersion()).isEqualTo(2);
        assertThat(v2.history()).hasSize(2);
        assertThat(v2.history().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(v2.history().get(1).status()).isEqualTo("CURRENT");
        assertThat(v2.history().get(1).totalCents()).isEqualTo(110);
    }

    @Test
    void publishVersionConflictReturns409AndWrongOpeningVersionReturns409() {
        long caseId = repairCaseWithTwoItems("TEMU3000015");
        publish(caseId, "pub-ok", 0);
        // 再次发布，期望序号仍是 0 但当前已有序号 1 → 409
        assertThatThrownBy(() -> publish(caseId, "pub-conflict", 0))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        // 已发布但未开标时再次发布也被业务拒绝（上一版未结束）
        long caseId2 = repairCaseWithTwoItems("TEMU3000016");
        long inquiry2 = publish(caseId2, "pub2-ok", 0);
        assertThatThrownBy(() -> publish(caseId2, "pub2-bad", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 开标版本号不匹配 → 409
        supplier("S801", List.of("WELD", "STRAIGHTEN", "REPLACE"));
        bid(inquiry2, "S801", 0, "WELD", 100, "REPLACE", 100, "b801");
        clock.set(AFTER_DEADLINE);
        assertThatThrownBy(() -> open(inquiry2, 7, "open-bad-ver"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---- 幂等 ----

    @Test
    void sameKeySameParamsReplayFirstResultAndDifferentParams409() {
        long caseId = repairCaseWithTwoItems("TEMU3000017");
        List<Long> items = itemIds(caseId);
        PublishInquiryRequest req = new PublishInquiryRequest("pub-idem", 0, DEADLINE, List.of(
                new InquiryItemSpec(items.get(0), List.of("WELD")),
                new InquiryItemSpec(items.get(1), List.of("WELD"))));
        InquiryView first = inquiryService.publishInquiry(caseId, req);
        InquiryView replay = inquiryService.publishInquiry(caseId, req);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from inquiry where case_id = ?", Integer.class, caseId))
                .isEqualTo(1);

        // 同键异参（不同截止时刻）→ 409，不新增
        PublishInquiryRequest other = new PublishInquiryRequest("pub-idem", 0,
                DEADLINE.plusSeconds(1), List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD")),
                        new InquiryItemSpec(items.get(1), List.of("WELD"))));
        assertThatThrownBy(() -> inquiryService.publishInquiry(caseId, other))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("select count(*) from inquiry where case_id = ?", Integer.class, caseId))
                .isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        long caseId = repairCaseWithTwoItems("TEMU3000018");
        long inquiryId = publish(caseId, "pub-fail", 0);
        supplier("S802", List.of("WELD", "STRAIGHTEN", "REPLACE"));

        // 首次使用该 requestId 提交不合格报价（缺行）失败
        String key = "bid-fail-key";
        assertThatThrownBy(() -> inquiryService.submitBid(inquiryId, "S802", new SubmitBidRequest(
                key, 0, List.of(new BidLineInput(1, "WELD", 100L)))))
                .isInstanceOf(ApiException.class);
        // 失败不占键：同键可成功提交合格报价
        BidView ok = inquiryService.submitBid(inquiryId, "S802", new SubmitBidRequest(
                key, 0, List.of(
                        new BidLineInput(1, "WELD", 100L),
                        new BidLineInput(2, "REPLACE", 200L))));
        assertThat(ok.currentVersion()).isEqualTo(1);
    }

    // ---- 发布前置与进行中守卫 ----

    @Test
    void publishOnlyForEstimatedRepairCaseAndGuardsLegacyEntries() {
        // 仅登记未估价 → 422（状态校验先于残损项，空集合即可触发）
        long registered = caseService.register(new RegisterRequest(
                "reg-x", "TEMU3000019", new BigDecimal("10000"))).id();
        assertThatThrownBy(() -> inquiryService.publishInquiry(registered, new PublishInquiryRequest(
                "pub-x", 0, DEADLINE, List.of(new InquiryItemSpec(1L, List.of("WELD"))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 全损案 → 422
        long tlCase = caseService.register(new RegisterRequest(
                "reg-tl", "TEMU3000020", new BigDecimal("10000"))).id();
        caseService.survey(tlCase, new SurveyRequest("svy-tl", List.of(
                new DamageItemInput("DN", "侧板", "重损", Responsibility.OWNER, new BigDecimal("3000.01")))));
        caseService.estimate(tlCase, new EstimateRequest("est-tl"));
        List<Long> tlItems = itemIds(tlCase);
        assertThatThrownBy(() -> inquiryService.publishInquiry(tlCase, new PublishInquiryRequest(
                "pub-tl", 0, DEADLINE, List.of(new InquiryItemSpec(tlItems.get(0), List.of("WELD"))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 询价进行中：旧批复入口与重新估价被拒
        long caseId = repairCaseWithTwoItems("TEMU3000021");
        long inquiryId = publish(caseId, "pub-g", 0);
        assertThatThrownBy(() -> caseService.approve(caseId, new ApprovalRequest("ap-g",
                java.time.LocalDate.parse("2026-09-21"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> caseService.estimate(caseId, new EstimateRequest("est-g")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 未知对象 → 404
        assertThatThrownBy(() -> inquiryService.getInquiry(999999))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> inquiryService.getBid(inquiryId, "NO_SUPPLIER"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownSupplierAndMethodsRejected() {
        assertThatThrownBy(() -> inquiryService.grantQualification("GHOST",
                new QualificationGrantRequest("q-ghost", 0, T0, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-unknown", "S999", "X", List.of("NOT_A_METHOD"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
