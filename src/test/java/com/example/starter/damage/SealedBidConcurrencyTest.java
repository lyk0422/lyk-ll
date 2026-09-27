package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.SealedRequests.BidLineInput;
import com.example.starter.damage.dto.SealedRequests.OpenBidsRequest;
import com.example.starter.damage.dto.SealedRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.SealedRequests.ReviewRequest;
import com.example.starter.damage.dto.SealedRequests.SubmitBidRequest;
import com.example.starter.damage.dto.SealedViews.BidView;
import com.example.starter.damage.dto.SealedViews.OpeningView;
import com.example.starter.support.MutableClock;
import com.example.starter.support.SealedClockConfig;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.SealedBidService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 密封报价并发与幂等边界测试：真实 H2 约束（唯一索引、行锁）与事务边界下，
 * 验证并发投标唯一受理、同 requestId 并发开标单一结果、并发复核只转一次批复，
 * 以及结构化指纹对含分隔符合法文本的无歧义区分。
 */
@SpringBootTest
@Import(SealedClockConfig.class)
class SealedBidConcurrencyTest {

    private static final int THREADS = 8;
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 9, 27, 1, 0, 0);

    @Autowired
    private SealedBidService sealed;
    @Autowired
    private DamageCaseService cases;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        jdbc.update("delete from bid_item");
        jdbc.update("delete from bid_exclusion");
        jdbc.update("delete from bid_review");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from bid");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from qualification_method");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        jdbc.update("insert into supplier_qualification(supplier_id, version, valid_from, valid_until,"
                        + " status, request_id, created_at)"
                        + " select id, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'ACTIVE', 'seed',"
                        + " '2026-01-01 00:00:00' from supplier");
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'HM' code union all select 'WD' union all select 'SP') m"
                + " where s.supplier_no = 'SUP-100'");
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'WD' code union all select 'RP') m"
                + " where s.supplier_no = 'SUP-200'");
        clock.setInstant(SealedClockConfig.START);
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private long prepareCase() {
        long caseId = cases.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), "TEMU5000001", new BigDecimal("10000.00"))).id();
        cases.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("500.00")))));
        cases.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        long i1 = cases.detail(caseId).items().get(0).id();
        long i2 = cases.detail(caseId).items().get(1).id();
        sealed.publishInquiry(caseId, new PublishInquiryRequest("pub-c", 1, DEADLINE, List.of(
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(i1, List.of("HM", "WD")),
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(i2, List.of("HM", "WD")))));
        return caseId;
    }

    private SubmitBidRequest twoLineBid(String requestId, String supplierNo, int revision,
                                        String m1, String m2) {
        return new SubmitBidRequest(requestId, supplierNo, revision, List.of(
                new BidLineInput(1, m1, 100L),
                new BidLineInput(2, m2, 100L)));
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

    @Test
    void concurrentFirstBidSameSupplier_onlyOneAccepted() throws Exception {
        long caseId = prepareCase();
        List<Object> results = runConcurrently(() -> {
            try {
                return sealed.submitBid(caseId, 1,
                        twoLineBid("bid-c-" + UUID.randomUUID(), "SUP-100", 1, "HM", "WD"));
            } catch (ApiException e) {
                return e;
            }
        });

        long accepted = results.stream().filter(BidView.class::isInstance).count();
        long conflicted = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(accepted).isEqualTo(1);
        assertThat(conflicted).isEqualTo(THREADS - 1);

        Integer totalBids = jdbc.queryForObject("select count(*) from bid", Integer.class);
        Integer currentBids = jdbc.queryForObject("select count(*) from bid where status = 'CURRENT'",
                Integer.class);
        assertThat(totalBids).isEqualTo(1);
        assertThat(currentBids).isEqualTo(1);
    }

    @Test
    void concurrentOpenSameRequestId_singleOpeningAndSameResult() throws Exception {
        long caseId = prepareCase();
        sealed.submitBid(caseId, 1, twoLineBid("bid-co-1", "SUP-100", 1, "HM", "WD"));
        clock.setInstant(DEADLINE.plusSeconds(1).toInstant(ZoneOffset.UTC));

        List<Object> results = runConcurrently(() -> {
            try {
                return sealed.openBids(caseId, 1, new OpenBidsRequest("open-c-1"));
            } catch (ApiException e) {
                return e;
            }
        });

        assertThat(results).allSatisfy(r -> {
            assertThat(r).isInstanceOf(OpeningView.class);
            assertThat(((OpeningView) r).winnerSupplierNo()).isEqualTo("SUP-100");
        });
        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(results.get(0)));
        Integer openings = jdbc.queryForObject("select count(*) from bid_opening", Integer.class);
        assertThat(openings).isEqualTo(1);
    }

    @Test
    void concurrentTwoDifferentReviewers_conversionHappensExactlyOnce() throws Exception {
        long caseId = prepareCase();
        sealed.submitBid(caseId, 1, twoLineBid("bid-cr-1", "SUP-100", 1, "HM", "WD"));
        clock.setInstant(DEADLINE.plusSeconds(1).toInstant(ZoneOffset.UTC));
        sealed.openBids(caseId, 1, new OpenBidsRequest("open-cr-1"));

        // 两个不同复核人并发提交：行锁串行化，恰好一人触发转批复，案件只产生一个 ACTIVE 批复。
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (String reviewer : List.of("alice", "bob")) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return sealed.review(caseId, 1,
                            new ReviewRequest("rv-c-" + reviewer, reviewer));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Object> future : futures) {
            Object r = future.get(30, TimeUnit.SECONDS);
            assertThat(r).isInstanceOf(OpeningView.class);
        }

        Integer approvals = jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ? and status = 'ACTIVE'",
                Integer.class, caseId);
        assertThat(approvals).isEqualTo(1);
        Integer reviews = jdbc.queryForObject("select count(*) from bid_review", Integer.class);
        assertThat(reviews).isEqualTo(2);
        assertThat(sealed.opening(caseId, 1).converted()).isTrue();
    }

    @Test
    void structuredFingerprintDistinguishesLegalTextContainingDelimiters() {
        long caseId = prepareCase();
        // 朴素 "|" 拼接下两次参数序列同为：SUP-100|1|HM|100|2|WD|200；
        // 结构化 JSON 指纹必须识别出集合结构不同，同键异参返回 409 而非重放。
        sealed.submitBid(caseId, 1, new SubmitBidRequest("bid-fp-1", "SUP-100", 1, List.of(
                new BidLineInput(1, "HM", 100L),
                new BidLineInput(2, "WD", 200L))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sealed.submitBid(caseId, 1,
                        new SubmitBidRequest("bid-fp-1", "SUP-100", 1, List.of(
                                new BidLineInput(1, "HM|100|2|WD", 200L)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        // 首次报价结果保持不变：两行、总价 300
        OpeningView unused = null;
        Integer total = jdbc.queryForObject("select total_cents from bid where status = 'CURRENT'",
                Integer.class);
        assertThat(total).isEqualTo(300);
        Integer lineCount = jdbc.queryForObject("select count(*) from bid_item", Integer.class);
        assertThat(lineCount).isEqualTo(2);
    }
}
