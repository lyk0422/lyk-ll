package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
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
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.InquiryViews.BidView;
import com.example.starter.damage.dto.InquiryViews.OpeningView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.InquiryService;
import org.junit.jupiter.api.AfterEach;
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
 * 密封询价并发与事务测试：真实 H2（MODE=MySQL）行锁与事务边界下，验证
 * 并发首次报价唯一受理、并发开标只产生一条冻结清单、开标与撤销资格竞争结果一致、
 * 并发双重复核只产生一个修理批复。
 */
@SpringBootTest
@Import(InquiryTestClockConfig.class)
class InquiryConcurrencyTest {

    private static final Instant T0 = InquiryTestClockConfig.BASE;
    private static final Instant DEADLINE = T0.plus(Duration.ofDays(1));

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private DamageCaseService caseService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
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
        pool = Executors.newFixedThreadPool(10);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private long setupCaseWithInquiryAndSupplier(String containerNo, String supplierNo, List<String> methods) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("500")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        List<Long> items = caseService.detail(caseId).items().stream()
                .map(i -> i.id()).toList();
        long inquiryId = inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                "pub-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "STRAIGHTEN")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE"))))).id();
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-" + UUID.randomUUID(), supplierNo, "供应商-" + supplierNo, methods));
        inquiryService.grantQualification(supplierNo, new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
        return inquiryId;
    }

    private SubmitBidRequest validBid(String key, int expectedVersion, String m1, String m2) {
        return new SubmitBidRequest(key, expectedVersion, List.of(
                new BidLineInput(1, m1, 1000L),
                new BidLineInput(2, m2, 2000L)));
    }

    private <T> List<T> runConcurrent(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return task.call();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentFirstBidOnlyOneCurrentVersion() throws Exception {
        long inquiryId = setupCaseWithInquiryAndSupplier("TEMU5000001", "C001", List.of("WELD"));

        int threads = 6;
        List<Object> results = runConcurrent(threads, () -> {
            try {
                return inquiryService.submitBid(inquiryId, "C001",
                        validBid("bid-conc-" + UUID.randomUUID(), 0, "WELD", "WELD"));
            } catch (ApiException e) {
                return e;
            }
        });

        long ok = results.stream().filter(BidView.class::isInstance).count();
        long conflict = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);

        Integer bids = jdbc.queryForObject("select count(*) from bid where inquiry_id = ?", Integer.class, inquiryId);
        Integer versions = jdbc.queryForObject(
                "select count(*) from bid_version bv join bid b on b.id = bv.bid_id where b.inquiry_id = ?",
                Integer.class, inquiryId);
        assertThat(bids).isEqualTo(1);
        assertThat(versions).isEqualTo(1);
    }

    @Test
    void concurrentOpenProducesSingleFrozenOpeningAndSameWinner() throws Exception {
        long inquiryId = setupCaseWithInquiryAndSupplier("TEMU5000002", "C002",
                List.of("WELD", "STRAIGHTEN", "REPLACE"));
        inquiryService.submitBid(inquiryId, "C002", validBid("b-" + UUID.randomUUID(), 0, "WELD", "REPLACE"));
        clock.set(DEADLINE.plus(Duration.ofMinutes(1)));

        int threads = 6;
        List<Object> results = runConcurrent(threads, () -> {
            try {
                return inquiryService.openBids(inquiryId,
                        new OpenBidsRequest("open-conc-" + UUID.randomUUID(), 1));
            } catch (ApiException e) {
                return e;
            }
        });

        // 全部返回同一条开标清单（业务级幂等），无异常
        assertThat(results).allMatch(OpeningView.class::isInstance);
        List<OpeningView> openings = results.stream().map(OpeningView.class::cast).toList();
        long openingId = openings.get(0).id();
        assertThat(openings).allSatisfy(o -> assertThat(o.id()).isEqualTo(openingId));
        assertThat(openings).allSatisfy(o -> assertThat(o.winnerSupplierNo()).isEqualTo("C002"));

        Integer openingRows = jdbc.queryForObject(
                "select count(*) from bid_opening where inquiry_id = ?", Integer.class, inquiryId);
        Integer entryRows = jdbc.queryForObject(
                "select count(*) from bid_opening_entry e join bid_opening o on o.id = e.opening_id"
                        + " where o.inquiry_id = ?", Integer.class, inquiryId);
        assertThat(openingRows).isEqualTo(1);
        assertThat(entryRows).isEqualTo(1);
    }

    @Test
    void openingAndRevocationRaceIsConsistent() throws Exception {
        long inquiryId = setupCaseWithInquiryAndSupplier("TEMU5000003", "C003",
                List.of("WELD", "STRAIGHTEN", "REPLACE"));
        inquiryService.submitBid(inquiryId, "C003", validBid("b-" + UUID.randomUUID(), 0, "WELD", "REPLACE"));
        Instant revokeAt = DEADLINE.plusSeconds(30);
        clock.set(revokeAt);

        // 开标与撤销同时发起，反复制造锁竞争
        int openThreads = 5;
        int total = openThreads + 1;
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < openThreads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return inquiryService.openBids(inquiryId,
                            new OpenBidsRequest("open-race-" + UUID.randomUUID(), 1));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return inquiryService.revokeQualification("C003",
                        new QualificationRevokeRequest("rev-race", 1, revokeAt));
            } catch (ApiException e) {
                return e;
            }
        }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<OpeningView> openings = new ArrayList<>();
        boolean revokeSucceeded = false;
        for (Future<Object> f : futures) {
            Object r = f.get(30, TimeUnit.SECONDS);
            if (r instanceof OpeningView ov) {
                openings.add(ov);
            } else if (r instanceof com.example.starter.damage.dto.InquiryViews.SupplierView) {
                revokeSucceeded = true;
            }
        }

        // 撤销必须成功（追加版本2）；开标唯一且与撤销顺序一致，不产生矛盾结果
        assertThat(revokeSucceeded).isTrue();
        assertThat(openings).isNotEmpty();
        long openingId = openings.get(0).id();
        assertThat(openings).allSatisfy(o -> assertThat(o.id()).isEqualTo(openingId));
        Integer openingRows = jdbc.queryForObject(
                "select count(*) from bid_opening where inquiry_id = ?", Integer.class, inquiryId);
        assertThat(openingRows).isEqualTo(1);

        Integer qualVersion = jdbc.queryForObject(
                "select max(version) from supplier_qualification sq join supplier s on s.id = sq.supplier_id"
                        + " where s.supplier_no = 'C003'", Integer.class);
        String qualStatus = jdbc.queryForObject(
                "select status from supplier_qualification sq join supplier s on s.id = sq.supplier_id"
                        + " where s.supplier_no = 'C003' order by version desc limit 1", String.class);
        assertThat(qualVersion).isEqualTo(2);
        assertThat(qualStatus).isEqualTo("REVOKED");

        OpeningView frozen = openings.get(0);
        if ("AWARDED".equals(frozen.status())) {
            // 开标先于撤销提交：中标行快照必须是开标时有效资格，且后来撤销不重算
            assertThat(frozen.winnerSupplierNo()).isEqualTo("C003");
            assertThat(frozen.entries()).singleElement().satisfies(e -> {
                assertThat(e.valid()).isTrue();
                assertThat(e.qualificationVersion()).isEqualTo(1);
            });
        } else {
            // 撤销先于开标提交：按开标时已失效资格排除，零有效报价
            assertThat(frozen.status()).isEqualTo("NO_VALID");
            assertThat(frozen.entries()).singleElement().satisfies(e -> {
                assertThat(e.valid()).isFalse();
                assertThat(e.excludeReason()).isEqualTo("QUALIFICATION_REVOKED");
                assertThat(e.qualificationVersion()).isEqualTo(2);
            });
        }
    }

    @Test
    void concurrentReviewsCreateExactlyOneApproval() throws Exception {
        long inquiryId = setupCaseWithInquiryAndSupplier("TEMU5000004", "C004",
                List.of("WELD", "STRAIGHTEN", "REPLACE"));
        inquiryService.submitBid(inquiryId, "C004", validBid("b-" + UUID.randomUUID(), 0, "WELD", "REPLACE"));
        clock.set(DEADLINE.plus(Duration.ofMinutes(1)));
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-1", 1));

        String[] reviewers = {"alice", "bob", "carol", "dave"};
        int threads = reviewers.length;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String reviewer = reviewers[i];
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return inquiryService.review(inquiryId,
                            new ReviewRequest("rv-" + reviewer + "-" + UUID.randomUUID(), reviewer));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }

        long approvals = jdbc.queryForObject(
                "select count(*) from repair_approval ra join inquiry i on i.case_id = ra.case_id"
                        + " where i.id = ? and ra.source = 'INQUIRY'", Integer.class, inquiryId);
        long reviewRows = jdbc.queryForObject(
                "select count(*) from bid_review where inquiry_id = ?", Integer.class, inquiryId);
        assertThat(approvals).isEqualTo(1);
        // 恰好两名不同复核人促成转批复
        assertThat(reviewRows).isEqualTo(2);

        // 转批复成功数恰为 1，其余为 422（同人重复或已转批复）
        long converted = results.stream()
                .filter(com.example.starter.damage.dto.InquiryViews.ReviewResultView.class::isInstance)
                .map(com.example.starter.damage.dto.InquiryViews.ReviewResultView.class::cast)
                .filter(r -> r.opening().converted())
                .count();
        assertThat(converted).isEqualTo(1);
        long rejected = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.UNPROCESSABLE_ENTITY)
                .count();
        assertThat(rejected).isEqualTo(reviewers.length - 2);

        String inquiryStatus = jdbc.queryForObject(
                "select status from inquiry where id = ?", String.class, inquiryId);
        Integer inquiryVersion = jdbc.queryForObject(
                "select version from inquiry where id = ?", Integer.class, inquiryId);
        assertThat(inquiryStatus).isEqualTo("CONVERTED");
        assertThat(inquiryVersion).isEqualTo(3);
    }
}
