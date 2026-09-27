package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.InquiryItemInput;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QualificationUpdateRequest;
import com.example.starter.damage.dto.Requests.QuotationLineInput;
import com.example.starter.damage.dto.Requests.QuotationReviseRequest;
import com.example.starter.damage.dto.Requests.QuotationSubmitRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReviewRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.InquiryDetailView;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.QuotationView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.InquiryService;
import com.example.starter.damage.service.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密封询价主流程测试：可控时钟下验证发布、投标/修订、截止边界、开标定标、
 * 双人复核、资格冻结、重复开标不重算、定标后撤销只形成风险事件与旧案批复兼容。
 */
@SpringBootTest
class InquiryFlowTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 9, 27, 10, 0);
    private static final LocalDateTime VALID_FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime VALID_UNTIL = LocalDateTime.of(2027, 1, 1, 0, 0);

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private DamageCaseService caseService;

    @Autowired
    private InquiryService inquiryService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from risk_event");
        jdbc.update("delete from award_review");
        jdbc.update("delete from bid_opening_entry");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from quotation_line");
        jdbc.update("delete from quotation");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from supplier_qual_method");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from supplier");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        clock.setInstant(T0);
    }

    private long newEstimatedCase(String containerNo, String... repairCosts) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        List<DamageItemInput> items = java.util.Arrays.stream(repairCosts)
                .map(cost -> new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal(cost)))
                .toList();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), items));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        return caseId;
    }

    private List<Long> itemIds(long caseId) {
        return caseService.detail(caseId).items().stream().map(item -> item.id()).toList();
    }

    private InquiryDetailView publish(long caseId, String requestId, List<Long> itemIds, LocalDateTime deadline) {
        List<InquiryItemInput> items = itemIds.stream()
                .map(id -> new InquiryItemInput(id, List.of("SEAL", "WELD")))
                .toList();
        return inquiryService.publish(caseId, new InquiryPublishRequest(requestId, items, deadline));
    }

    private long newSupplier(String supplierNo, List<String> methods,
                             LocalDateTime validFrom, LocalDateTime validUntil) {
        return supplierService.create(new SupplierCreateRequest(
                "sup-" + UUID.randomUUID(), supplierNo, "供应商" + supplierNo, methods, validFrom, validUntil)).id();
    }

    private long newSupplier(String supplierNo) {
        return newSupplier(supplierNo, List.of("SEAL", "WELD"), VALID_FROM, VALID_UNTIL);
    }

    private QuotationView bid(long inquiryId, String requestId, long supplierId,
                              List<InquiryItemView> items, long amountPerLine) {
        List<QuotationLineInput> lines = items.stream()
                .map(item -> new QuotationLineInput(item.itemId(), "SEAL", amountPerLine))
                .toList();
        return inquiryService.submit(inquiryId, new QuotationSubmitRequest(requestId, supplierId, lines));
    }

    private void assertStatus(Runnable runnable, HttpStatus expected) {
        try {
            runnable.run();
            org.assertj.core.api.Assertions.fail("预期抛出 " + expected);
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(expected);
        }
    }

    @Test
    void fullInquiryFlowAwardsAndFreezes() {
        long caseId = newEstimatedCase("TEMU5000001", "1000.00", "500.00");
        List<Long> items = itemIds(caseId);
        InquiryDetailView published = publish(caseId, "inq-1", items, DEADLINE);
        long inquiryId = published.inquiry().id();
        assertThat(published.inquiry().version()).isEqualTo(1);
        assertThat(published.inquiry().status()).isEqualTo("OPEN");
        assertThat(published.items()).hasSize(2);

        long supplierA = newSupplier("SUP100");
        long supplierB = newSupplier("SUP101");
        QuotationView bidA = bid(inquiryId, "bid-a1", supplierA, published.items(), 800);
        assertThat(bidA.version()).isEqualTo(1);
        assertThat(bidA.totalAmount()).isEqualTo(1600);
        bid(inquiryId, "bid-b1", supplierB, published.items(), 600);

        // 修订保留历史：A 降价至每行 550
        List<QuotationLineInput> revisedLines = published.items().stream()
                .map(item -> new QuotationLineInput(item.itemId(), "SEAL", 550L))
                .toList();
        QuotationView revised = inquiryService.revise(inquiryId, supplierA,
                new QuotationReviseRequest("bid-a2", 1L, revisedLines));
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.totalAmount()).isEqualTo(1100);
        InquiryDetailView afterRevise = inquiryService.detail(inquiryId);
        assertThat(afterRevise.quotations()).hasSize(3);
        assertThat(afterRevise.quotations().stream()
                .filter(q -> q.supplierId() == supplierA && q.version() == 1)
                .findFirst().orElseThrow().status()).isEqualTo("SUPERSEDED");

        clock.advance(Duration.ofHours(3)); // 11:00，已过截止
        BidOpeningView opening = inquiryService.open(inquiryId, new BidOpenRequest("open-1"));
        assertThat(opening.winnerQuotationId()).isEqualTo(revised.id());
        assertThat(opening.entries()).hasSize(2);
        assertThat(opening.entries().get(0).rank()).isEqualTo(1);
        assertThat(opening.entries().get(0).supplierId()).isEqualTo(supplierA);
        assertThat(opening.entries().get(1).rank()).isEqualTo(2);
        assertThat(opening.entries()).allSatisfy(e -> {
            assertThat(e.included()).isTrue();
            assertThat(e.exclusionReason()).isNull();
        });

        // 开标后当前报价已冻结（历史版本保留未冻结原样）
        assertThat(inquiryService.detail(inquiryId).quotations().stream()
                .filter(q -> q.status().equals("CURRENT")))
                .allSatisfy(q -> assertThat(q.frozen()).isTrue());

        // 双人不同复核才定标
        InquiryDetailView firstReview = inquiryService.review(inquiryId, new ReviewRequest("rev-1", "张三"));
        assertThat(firstReview.inquiry().status()).isEqualTo("OPENED");
        assertThat(firstReview.reviews()).hasSize(1);
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.ESTIMATED);

        InquiryDetailView awarded = inquiryService.review(inquiryId, new ReviewRequest("rev-2", "李四"));
        assertThat(awarded.inquiry().status()).isEqualTo("AWARDED");
        assertThat(awarded.reviews()).hasSize(2);

        // 转为原案修理批复
        var caseDetail = caseService.detail(caseId);
        assertThat(caseDetail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(caseDetail.approvals()).hasSize(1);
        assertThat(caseDetail.approvals().get(0).status()).isEqualTo("ACTIVE");

        // 资格版本已冻结
        assertThat(supplierService.detail(supplierA).qualifications())
                .allSatisfy(q -> assertThat(q.frozen()).isTrue());
        assertThat(supplierService.detail(supplierB).qualifications())
                .allSatisfy(q -> assertThat(q.frozen()).isTrue());
    }

    @Test
    void bidExactlyAtDeadlineRejected() {
        long caseId = newEstimatedCase("TEMU5000002", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-2", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP110");

        clock.setInstant(Instant.parse("2026-09-27T09:59:59Z"));
        bid(inquiryId, "bid-ok", supplier, published.items(), 100); // now<截止 可投

        long supplier2 = newSupplier("SUP111");
        clock.setInstant(Instant.parse("2026-09-27T10:00:00Z")); // 恰在截止
        assertStatus(() -> bid(inquiryId, "bid-late", supplier2, published.items(), 100),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void openBeforeDeadlineRejected() {
        long caseId = newEstimatedCase("TEMU5000003", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-3", itemIds(caseId), DEADLINE);

        assertStatus(() -> inquiryService.open(published.inquiry().id(), new BidOpenRequest("open-early")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void quotationValidationFailuresRejectWholeVersion() {
        long caseId = newEstimatedCase("TEMU5000004", "1000.00", "500.00");
        List<Long> items = itemIds(caseId);
        InquiryDetailView published = publish(caseId, "inq-4", items, DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP120");
        List<InquiryItemView> inquiryItems = published.items();

        // 未覆盖全部项 → 422 整版拒绝
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad1", supplier,
                        List.of(new QuotationLineInput(inquiryItems.get(0).itemId(), "SEAL", 100L)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 金额非正整数分 → 400
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad2", supplier,
                        inquiryItems.stream().map(i -> new QuotationLineInput(i.itemId(), "SEAL", 0L)).toList())),
                HttpStatus.BAD_REQUEST);
        // 工法不在该项允许集合 → 422
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad3", supplier,
                        inquiryItems.stream().map(i -> new QuotationLineInput(i.itemId(), "PATCH", 100L)).toList())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 未知工法 → 400
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad4", supplier,
                        inquiryItems.stream().map(i -> new QuotationLineInput(i.itemId(), "XX", 100L)).toList())),
                HttpStatus.BAD_REQUEST);
        // 重复报价行 → 422
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad5", supplier,
                        List.of(new QuotationLineInput(inquiryItems.get(0).itemId(), "SEAL", 100L),
                                new QuotationLineInput(inquiryItems.get(0).itemId(), "SEAL", 100L)))),
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 任一不符整版拒绝：无报价版本落库
        Integer count = jdbc.queryForObject("select count(*) from quotation where inquiry_id = ?",
                Integer.class, inquiryId);
        assertThat(count).isEqualTo(0);

        // 供应商无该工法资格 → 422
        long weldOnly = newSupplier("SUP121", List.of("WELD"), VALID_FROM, VALID_UNTIL);
        assertStatus(() -> inquiryService.submit(inquiryId, new QuotationSubmitRequest("q-bad6", weldOnly,
                        inquiryItems.stream().map(i -> new QuotationLineInput(i.itemId(), "SEAL", 100L)).toList())),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void expiredQualificationAtOpeningExcludedWithReason() {
        long caseId = newEstimatedCase("TEMU5000005", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-5", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        // 投标时资格有效，但有效期到 09:30，开标时已过期；不得采用提交时旧资格
        long expiring = newSupplier("SUP130", List.of("SEAL"),
                VALID_FROM, LocalDateTime.of(2026, 9, 27, 9, 30));
        long healthy = newSupplier("SUP131");
        bid(inquiryId, "bid-exp", expiring, published.items(), 100);
        bid(inquiryId, "bid-ok", healthy, published.items(), 200);

        clock.advance(Duration.ofHours(3));
        BidOpeningView opening = inquiryService.open(inquiryId, new BidOpenRequest("open-5"));

        var excluded = opening.entries().stream().filter(e -> e.supplierId() == expiring).findFirst().orElseThrow();
        assertThat(excluded.included()).isFalse();
        assertThat(excluded.exclusionReason()).contains("过期");
        assertThat(excluded.rank()).isNull();
        var included = opening.entries().stream().filter(e -> e.supplierId() == healthy).findFirst().orElseThrow();
        assertThat(included.included()).isTrue();
        assertThat(opening.winnerQuotationId()).isNotNull();
    }

    @Test
    void tieBreakBySupplierNoAscending() {
        long caseId = newEstimatedCase("TEMU5000006", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-6", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long high = newSupplier("SUP201");
        long low = newSupplier("SUP200");
        QuotationView bidHigh = bid(inquiryId, "bid-h", high, published.items(), 300);
        QuotationView bidLow = bid(inquiryId, "bid-l", low, published.items(), 300);

        clock.advance(Duration.ofHours(3));
        BidOpeningView opening = inquiryService.open(inquiryId, new BidOpenRequest("open-6"));

        assertThat(opening.winnerQuotationId()).isEqualTo(bidLow.id());
        assertThat(opening.entries().get(0).supplierNo()).isEqualTo("SUP200");
        assertThat(opening.entries().get(1).supplierNo()).isEqualTo("SUP201");
    }

    @Test
    void zeroValidQuotationsOpensNoApproval() {
        long caseId = newEstimatedCase("TEMU5000007", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-7", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP140");
        bid(inquiryId, "bid-z", supplier, published.items(), 100);
        // 截止后、开标前撤销资格
        clock.advance(Duration.ofHours(3));
        supplierService.revoke(supplier, new QualificationRevokeRequest("rev-sup", 1L));

        BidOpeningView opening = inquiryService.open(inquiryId, new BidOpenRequest("open-7"));
        assertThat(opening.winnerQuotationId()).isNull();
        assertThat(opening.entries().get(0).included()).isFalse();
        assertThat(opening.entries().get(0).exclusionReason()).contains("撤销");

        // 零有效报价不开批复
        assertStatus(() -> inquiryService.review(inquiryId, new ReviewRequest("rev-z1", "张三")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.ESTIMATED);
        assertThat(caseService.detail(caseId).approvals()).isEmpty();
    }

    @Test
    void repeatedOpeningReturnsFirstListWithoutRecompute() {
        long caseId = newEstimatedCase("TEMU5000008", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-8", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP150");
        bid(inquiryId, "bid-r", supplier, published.items(), 100);

        clock.advance(Duration.ofHours(3));
        BidOpeningView first = inquiryService.open(inquiryId, new BidOpenRequest("open-8"));
        assertThat(first.entries().get(0).included()).isTrue();

        // 开标后资格变化不影响首次清单
        supplierService.revoke(supplier, new QualificationRevokeRequest("rev-after-open", 1L));
        BidOpeningView again = inquiryService.open(inquiryId, new BidOpenRequest("open-8b"));
        assertThat(again).isEqualTo(first);
        assertThat(again.entries().get(0).included()).isTrue();

        Integer openings = jdbc.queryForObject("select count(*) from bid_opening where inquiry_id = ?",
                Integer.class, inquiryId);
        assertThat(openings).isEqualTo(1);
    }

    @Test
    void revokeAfterAwardCreatesRiskEventOnly() {
        long caseId = newEstimatedCase("TEMU5000009", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-9", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP160");
        bid(inquiryId, "bid-w", supplier, published.items(), 100);
        clock.advance(Duration.ofHours(3));
        inquiryService.open(inquiryId, new BidOpenRequest("open-9"));
        inquiryService.review(inquiryId, new ReviewRequest("rev-91", "张三"));
        inquiryService.review(inquiryId, new ReviewRequest("rev-92", "李四"));
        assertThat(inquiryService.detail(inquiryId).inquiry().status()).isEqualTo("AWARDED");

        // 批复后撤销只形成风险事件，不改历史中标证据
        supplierService.revoke(supplier, new QualificationRevokeRequest("rev-post-award", 1L));
        var supplierDetail = supplierService.detail(supplier);
        assertThat(supplierDetail.riskEvents()).hasSize(1);
        assertThat(supplierDetail.riskEvents().get(0).eventType()).isEqualTo("QUALIFICATION_REVOKED_AFTER_AWARD");
        assertThat(supplierDetail.riskEvents().get(0).caseId()).isEqualTo(caseId);
        assertThat(supplierDetail.riskEvents().get(0).inquiryId()).isEqualTo(inquiryId);

        BidOpeningView opening = inquiryService.detail(inquiryId).opening();
        assertThat(opening.entries().get(0).included()).isTrue();
        assertThat(opening.winnerQuotationId()).isNotNull();
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
    }

    @Test
    void sameReviewerTwiceRejected() {
        long caseId = newEstimatedCase("TEMU5000010", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-10", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        bid(inquiryId, "bid-s", newSupplier("SUP170"), published.items(), 100);
        clock.advance(Duration.ofHours(3));
        inquiryService.open(inquiryId, new BidOpenRequest("open-10"));
        inquiryService.review(inquiryId, new ReviewRequest("rev-101", "张三"));

        assertStatus(() -> inquiryService.review(inquiryId, new ReviewRequest("rev-102", "张三")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(inquiryService.detail(inquiryId).inquiry().status()).isEqualTo("OPENED");
    }

    @Test
    void totalLossOrUnestimatedCaseCannotPublish() {
        // 全损案不得发布询价（原全损判定仍适用）
        long totalLossCase = newEstimatedCase("TEMU5000011", "3000.01");
        assertThat(caseService.detail(totalLossCase).caseInfo().determination()).isEqualTo(Determination.TOTAL_LOSS);
        assertStatus(() -> publish(totalLossCase, "inq-tl", itemIds(totalLossCase), DEADLINE),
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 未估价案不得发布
        long surveyedOnly = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), "TEMU5000012", new BigDecimal("10000.00"))).id();
        caseService.survey(surveyedOnly, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")))));
        assertStatus(() -> publish(surveyedOnly, "inq-sv", itemIds(surveyedOnly), DEADLINE),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void awardRejectedWhenCaseTurnedTotalLoss() {
        long caseId = newEstimatedCase("TEMU5000013", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-13", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        bid(inquiryId, "bid-13", newSupplier("SUP180"), published.items(), 100);
        clock.advance(Duration.ofHours(3));
        inquiryService.open(inquiryId, new BidOpenRequest("open-13"));
        inquiryService.review(inquiryId, new ReviewRequest("rev-131", "张三"));

        // 原案判定转为全损（合成状态迁移），定标须拒绝
        jdbc.update("update damage_case set determination = 'TOTAL_LOSS' where id = ?", caseId);
        assertStatus(() -> inquiryService.review(inquiryId, new ReviewRequest("rev-132", "李四")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(caseService.detail(caseId).approvals()).isEmpty();
    }

    @Test
    void legacyApproveWithoutInquiryStillWorks() {
        long caseId = newEstimatedCase("TEMU5000014", "1000.00");
        caseService.approve(caseId, new ApprovalRequest("ap-legacy", LocalDate.of(2026, 9, 27)));
        var detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.approvals()).hasSize(1);
    }

    @Test
    void newInquiryVersionSupersedesOpenOne() {
        long caseId = newEstimatedCase("TEMU5000015", "1000.00");
        InquiryDetailView first = publish(caseId, "inq-15a", itemIds(caseId), DEADLINE);
        InquiryDetailView second = publish(caseId, "inq-15b", itemIds(caseId), DEADLINE.plusHours(1));

        assertThat(second.inquiry().version()).isEqualTo(2);
        var inquiries = inquiryService.listByCase(caseId);
        assertThat(inquiries).hasSize(2);
        assertThat(inquiries.get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(inquiries.get(1).status()).isEqualTo("OPEN");

        // 旧版本不再接受投标
        assertStatus(() -> bid(first.inquiry().id(), "bid-old", newSupplier("SUP190"), first.items(), 100),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void publishValidationErrors() {
        long caseId = newEstimatedCase("TEMU5000016", "1000.00");
        List<Long> items = itemIds(caseId);

        // 截止时刻不晚于当前 → 400
        assertStatus(() -> publish(caseId, "inq-p1", items, LocalDateTime.of(2026, 9, 27, 8, 0)),
                HttpStatus.BAD_REQUEST);
        // 非本案残损项 → 422
        assertStatus(() -> publish(caseId, "inq-p2", List.of(999999L), DEADLINE),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 未知工法 → 400
        assertStatus(() -> inquiryService.publish(caseId, new InquiryPublishRequest("inq-p3",
                        List.of(new InquiryItemInput(items.get(0), List.of("XX"))), DEADLINE)),
                HttpStatus.BAD_REQUEST);
        // 询价不存在 → 404
        assertStatus(() -> inquiryService.detail(999999), HttpStatus.NOT_FOUND);
    }

    @Test
    void idempotentSubmitAndConflict() {
        long caseId = newEstimatedCase("TEMU5000017", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-17", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP195");

        QuotationView first = bid(inquiryId, "bid-idem", supplier, published.items(), 100);
        QuotationView replay = bid(inquiryId, "bid-idem", supplier, published.items(), 100);
        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "select count(*) from quotation where inquiry_id = ?", Integer.class, inquiryId);
        assertThat(count).isEqualTo(1);

        // 同键异参 → 409，且不产生新版本
        assertStatus(() -> bid(inquiryId, "bid-idem", supplier, published.items(), 200), HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("select count(*) from quotation where inquiry_id = ?",
                Integer.class, inquiryId)).isEqualTo(1);

        // 一供应商一询价只一个当前报价：不同 requestId 重复提交 → 422
        assertStatus(() -> bid(inquiryId, "bid-other", supplier, published.items(), 100),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reviseWithStaleVersionReturns409() {
        long caseId = newEstimatedCase("TEMU5000018", "1000.00");
        InquiryDetailView published = publish(caseId, "inq-18", itemIds(caseId), DEADLINE);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP196");
        bid(inquiryId, "bid-v1", supplier, published.items(), 100);

        List<QuotationLineInput> lines = published.items().stream()
                .map(item -> new QuotationLineInput(item.itemId(), "SEAL", 90L))
                .toList();
        assertStatus(() -> inquiryService.revise(inquiryId, supplier,
                new QuotationReviseRequest("bid-v2", 99L, lines)), HttpStatus.CONFLICT);
        // 未存在的报价修订 → 404
        assertStatus(() -> inquiryService.revise(inquiryId, newSupplier("SUP197"),
                new QuotationReviseRequest("bid-v3", 1L, lines)), HttpStatus.NOT_FOUND);
    }
}
