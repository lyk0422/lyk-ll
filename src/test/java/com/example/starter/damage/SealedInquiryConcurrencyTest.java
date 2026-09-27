package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.BidItemInput;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.BidSubmitRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.InquiryItemSpec;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.OpeningLineView;
import com.example.starter.damage.dto.Views.OpeningView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.SealedInquiryService;
import com.example.starter.damage.support.MutableUtcClock;
import com.example.starter.damage.support.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 密封询价并发与幂等测试：真实 H2 事务与行锁下验证
 * 同 requestId 并发投标只产生一个修订、并发起标只产生一份冻结清单、
 * 开标与资格撤销竞争时结果与最终数据一致（不出现中标的同时资格已撤销等矛盾状态）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SealedInquiryConcurrencyTest {

    private static final int THREADS = 8;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 0, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 1, 12, 0, 0);

    @Autowired
    private SealedInquiryService inquiryService;
    @Autowired
    private DamageCaseService caseService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableUtcClock clock;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from repair_award");
        jdbc.update("delete from bid_opening_line");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from bid_item");
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
        clock.setTime(T0);
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private long prepareCase(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + containerNo, containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + containerNo, List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("100.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("200.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + containerNo));
        return caseId;
    }

    private InquiryView publish(long caseId) {
        List<Long> itemIds = caseService.detail(caseId).items().stream()
                .map(com.example.starter.damage.dto.Views.DamageItemView::id).toList();
        return inquiryService.publishInquiry(caseId, new InquiryPublishRequest(
                "inq-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                new InquiryItemSpec(itemIds.get(0), List.of("WELD")),
                new InquiryItemSpec(itemIds.get(1), List.of("PATCH")))));
    }

    private void supplier(String code) {
        inquiryService.createSupplier(new SupplierCreateRequest(
                "sup-" + code, code, "供应商" + code, List.of("WELD", "PATCH"),
                T0.minusDays(10), DEADLINE.plusDays(10)));
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
    void concurrentSameBidRequest_singleRevisionAndSameReplay() throws Exception {
        long caseId = prepareCase("TEMU3200001");
        InquiryView inquiry = publish(caseId);
        supplier("SUP-A");
        List<Long> inquiryItemIds = inquiry.items().stream().map(InquiryItemView::id).toList();

        List<?> results = runConcurrently(() -> inquiryService.submitBid(inquiry.id(), new BidSubmitRequest(
                "bid-conc-1", "SUP-A", 0, List.of(
                new BidItemInput(inquiryItemIds.get(0), "WELD", 1000),
                new BidItemInput(inquiryItemIds.get(1), "PATCH", 2000)))));

        assertThat(results).hasSize(THREADS);
        assertThat(results).allSatisfy(r -> {
            assertThat(r).isNotInstanceOf(ApiException.class);
            assertThat(r.toString()).contains("revision=1");
        });
        Integer bidRows = jdbc.queryForObject(
                "select count(*) from bid where inquiry_id = ?", Integer.class, inquiry.id());
        assertThat(bidRows).isEqualTo(1);
        Integer itemRows = jdbc.queryForObject(
                "select count(*) from bid_item bi join bid b on bi.bid_id = b.id where b.inquiry_id = ?",
                Integer.class, inquiry.id());
        assertThat(itemRows).isEqualTo(2);
    }

    @Test
    void concurrentOpenings_singleFrozenResultReturnedToAll() throws Exception {
        long caseId = prepareCase("TEMU3200002");
        InquiryView inquiry = publish(caseId);
        supplier("SUP-A");
        supplier("SUP-B");
        var ids = inquiry.items().stream().map(InquiryItemView::id).toList();
        inquiryService.submitBid(inquiry.id(), new BidSubmitRequest("bid-a", "SUP-A", 0, List.of(
                new BidItemInput(ids.get(0), "WELD", 1000),
                new BidItemInput(ids.get(1), "PATCH", 2000))));
        inquiryService.submitBid(inquiry.id(), new BidSubmitRequest("bid-b", "SUP-B", 0, List.of(
                new BidItemInput(ids.get(0), "WELD", 1500),
                new BidItemInput(ids.get(1), "PATCH", 1400))));
        clock.setTime(DEADLINE.plusMinutes(1));

        List<OpeningView> results = runConcurrently(
                () -> inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-conc-1")));

        assertThat(results).allSatisfy(o -> {
            assertThat(o.id()).isEqualTo(results.get(0).id());
            assertThat(o.winnerSupplierCode()).isEqualTo("SUP-B");
            assertThat(o.lines()).hasSize(2);
        });
        Integer openings = jdbc.queryForObject(
                "select count(*) from bid_opening where inquiry_id = ?", Integer.class, inquiry.id());
        assertThat(openings).isEqualTo(1);
        Integer lines = jdbc.queryForObject(
                "select count(*) from bid_opening_line l join bid_opening o on l.opening_id = o.id"
                        + " where o.inquiry_id = ?", Integer.class, inquiry.id());
        assertThat(lines).isEqualTo(2);
        // 全部当前报价均已冻结，没有遗留 CURRENT
        Integer currentLeft = jdbc.queryForObject(
                "select count(*) from bid where inquiry_id = ? and status = 'CURRENT'", Integer.class, inquiry.id());
        assertThat(currentLeft).isZero();

        // 开标后再次用不同 requestId 并发开标，仍全部返回首次清单
        List<OpeningView> replays = runConcurrently(
                () -> inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-conc-2")));
        assertThat(replays).allSatisfy(o -> assertThat(o.id()).isEqualTo(results.get(0).id()));
        assertThat(jdbc.queryForObject(
                "select count(*) from bid_opening where inquiry_id = ?", Integer.class, inquiry.id()))
                .isEqualTo(1);
    }

    @Test
    void openingRacesRevoke_consistentOutcomeUnderRowLocks() throws Exception {
        long caseId = prepareCase("TEMU3200003");
        InquiryView inquiry = publish(caseId);
        supplier("SUP-A");
        var ids = inquiry.items().stream().map(InquiryItemView::id).toList();
        inquiryService.submitBid(inquiry.id(), new BidSubmitRequest("bid-a", "SUP-A", 0, List.of(
                new BidItemInput(ids.get(0), "WELD", 1000),
                new BidItemInput(ids.get(1), "PATCH", 2000))));
        clock.setTime(DEADLINE.plusMinutes(1));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-race"));
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return inquiryService.revokeQualification("SUP-A",
                    new QualificationRevokeRequest("revoke-race", 1, "合成：与开标并发"));
        }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        OpeningView opening = null;
        for (Future<Object> future : futures) {
            Object result = future.get(30, TimeUnit.SECONDS);
            if (result instanceof OpeningView o) {
                opening = o;
            }
        }
        assertThat(opening).isNotNull();

        // 撤销必然已落库；开标清单必须与竞争先后严格一致：
        // 撤销先提交 → 报价被排除且原因 REVOKED；开标先提交 → 报价冻结为有效且中标，撤销不改历史证据。
        SupplierView supplier = inquiryService.supplierDetail("SUP-A");
        assertThat(supplier.status()).isEqualTo("REVOKED");
        assertThat(supplier.version()).isEqualTo(2);
        OpeningLineView line = opening.lines().get(0);
        if (line.qualified()) {
            assertThat(line.excludeReason()).isNull();
            assertThat(opening.winnerSupplierCode()).isEqualTo("SUP-A");
        } else {
            assertThat(line.excludeReason()).isEqualTo("SUPPLIER_QUALIFICATION_REVOKED");
            assertThat(opening.winnerSupplierCode()).isNull();
        }

        // 重复开标返回首次结果，撤销后的资格变化不触发重算
        OpeningView replay = inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-race-replay"));
        assertThat(replay.id()).isEqualTo(opening.id());
        assertThat(replay.lines().get(0).qualified()).isEqualTo(line.qualified());
    }
}
