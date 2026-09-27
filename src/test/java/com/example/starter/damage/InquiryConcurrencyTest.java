package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.InquiryItemInput;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QuotationLineInput;
import com.example.starter.damage.dto.Requests.QuotationSubmitRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReviewRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.InquiryDetailView;
import com.example.starter.damage.dto.Views.QuotationView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.InquiryService;
import com.example.starter.damage.service.SupplierService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 询价并发测试：真实 H2 约束与事务边界下协调真实并发，
 * 验证同 requestId 并发投标唯一受理、开标与撤销资格竞争一致、双人复核只定标一次。
 */
@SpringBootTest
class InquiryConcurrencyTest {

    private static final int THREADS = 8;
    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 9, 27, 10, 0);

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

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
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
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private <T> List<T> runConcurrently(java.util.concurrent.Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return task.call();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private long newEstimatedCase(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        return caseId;
    }

    private long newSupplier(String supplierNo) {
        return supplierService.create(new SupplierCreateRequest("sup-" + UUID.randomUUID(), supplierNo,
                "供应商" + supplierNo, List.of("SEAL"),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0))).id();
    }

    private InquiryDetailView publishOpenInquiry(long caseId) {
        List<Long> itemIds = caseService.detail(caseId).items().stream().map(item -> item.id()).toList();
        List<InquiryItemInput> items = itemIds.stream()
                .map(id -> new InquiryItemInput(id, List.of("SEAL")))
                .toList();
        return inquiryService.publish(caseId,
                new InquiryPublishRequest("inq-" + UUID.randomUUID(), items, DEADLINE));
    }

    @Test
    void concurrentSameSubmitRequest_singleQuotationAndSameResult() throws Exception {
        long caseId = newEstimatedCase("TEMU6000001");
        InquiryDetailView published = publishOpenInquiry(caseId);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP300");
        long itemId = published.items().get(0).itemId();

        List<QuotationView> results = runConcurrently(() -> inquiryService.submit(inquiryId,
                new QuotationSubmitRequest("bid-conc-1", supplier,
                        List.of(new QuotationLineInput(itemId, "SEAL", 500L)))));

        assertThat(results).allSatisfy(q -> {
            assertThat(q.version()).isEqualTo(1);
            assertThat(q.totalAmount()).isEqualTo(500);
        });
        assertThat(results).allSatisfy(q -> assertThat(q).isEqualTo(results.get(0)));
        Integer count = jdbc.queryForObject("select count(*) from quotation where inquiry_id = ?",
                Integer.class, inquiryId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentOpenAndRevoke_consistentOutcome() throws Exception {
        long caseId = newEstimatedCase("TEMU6000002");
        InquiryDetailView published = publishOpenInquiry(caseId);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP301");
        long itemId = published.items().get(0).itemId();
        inquiryService.submit(inquiryId, new QuotationSubmitRequest("bid-conc-2", supplier,
                List.of(new QuotationLineInput(itemId, "SEAL", 500L))));
        clock.advance(Duration.ofHours(3)); // 截止之后

        java.util.concurrent.atomic.AtomicInteger slot = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> results = runConcurrently(() -> {
            if (slot.getAndIncrement() == 0) {
                // 仅一个线程执行撤销，其余线程并发开标
                try {
                    return supplierService.revoke(supplier,
                            new QualificationRevokeRequest("rev-conc-1", 1L));
                } catch (ApiException e) {
                    return e;
                }
            }
            try {
                return inquiryService.open(inquiryId, new BidOpenRequest("open-conc-" + UUID.randomUUID()));
            } catch (ApiException e) {
                return e;
            }
        });

        // 全部操作正常完成（撤销在定标前不构成风险事件，开标只发生一次）
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(ApiException.class));
        Integer openings = jdbc.queryForObject("select count(*) from bid_opening where inquiry_id = ?",
                Integer.class, inquiryId);
        assertThat(openings).isEqualTo(1);

        BidOpeningView opening = inquiryService.detail(inquiryId).opening();
        assertThat(opening.entries()).hasSize(1);
        var entry = opening.entries().get(0);
        // 竞争结果须一致：要么开标先于撤销（含该报价），要么撤销先于开标（排除并列明原因）
        if (entry.included()) {
            assertThat(opening.winnerQuotationId()).isNotNull();
        } else {
            assertThat(entry.exclusionReason()).contains("撤销");
            assertThat(opening.winnerQuotationId()).isNull();
        }
    }

    @Test
    void concurrentDistinctReviews_awardOnlyOnce() throws Exception {
        long caseId = newEstimatedCase("TEMU6000003");
        InquiryDetailView published = publishOpenInquiry(caseId);
        long inquiryId = published.inquiry().id();
        long supplier = newSupplier("SUP302");
        long itemId = published.items().get(0).itemId();
        inquiryService.submit(inquiryId, new QuotationSubmitRequest("bid-conc-3", supplier,
                List.of(new QuotationLineInput(itemId, "SEAL", 500L))));
        clock.advance(Duration.ofHours(3));
        inquiryService.open(inquiryId, new BidOpenRequest("open-conc-3"));

        List<String> reviewers = List.of("张三", "李四");
        List<Object> results = runConcurrently(() -> {
            String reviewer = reviewers.get((int) (Thread.currentThread().threadId() % 2));
            try {
                return inquiryService.review(inquiryId,
                        new ReviewRequest("rev-conc-" + reviewer + "-" + UUID.randomUUID(), reviewer));
            } catch (ApiException e) {
                return e;
            }
        });

        InquiryDetailView detail = inquiryService.detail(inquiryId);
        assertThat(detail.inquiry().status()).isEqualTo("AWARDED");
        assertThat(detail.reviews()).hasSize(2);
        // 同一复核人的多余并发请求被 422 拒绝
        long rejected = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(rejected).isEqualTo(THREADS - 2);
        // 只定标一次：一个有效批复，案件进入已批复
        assertThat(caseService.detail(caseId).approvals()).hasSize(1);
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
    }
}
