package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.ReviewChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import com.example.starter.damage.dto.InquiryRequests.BidLineInput;
import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.InquiryItemSpec;
import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.CompletionView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.service.ChangeOrderService;
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
import java.time.LocalDate;
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
 * 修理变更单并发与事务测试：真实 H2（MODE=MySQL）行锁下验证
 * 完工与候选建立竞争的一致次序（不能已完工又改批复/建候选）、
 * 批准生效与资格撤销竞争的一致次序、并发候选唯一受理、并发复核只换版一次。
 */
@SpringBootTest
@Import(InquiryTestClockConfig.class)
class ChangeOrderConcurrencyTest {

    private static final Instant T0 = InquiryTestClockConfig.BASE;
    private static final Instant DEADLINE = T0.plus(Duration.ofDays(1));
    private static final Instant AFTER_DEADLINE = DEADLINE.plus(Duration.ofMinutes(1));

    @Autowired
    private ChangeOrderService changeOrderService;
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
        jdbc.update("delete from change_order_review");
        jdbc.update("delete from change_order_line");
        jdbc.update("delete from change_order");
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

    /** 经密封询价转批复的 APPROVED 案件，返回案件 ID；中标供应商 S-WIN 具备全部工法 */
    private long approvedCase(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("500.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        List<Long> items = caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
        long inquiryId = inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                "pub-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "PATCH")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE"))))).id();
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-" + UUID.randomUUID(), "S-WIN", "中标供应商",
                List.of("WELD", "STRAIGHTEN", "REPLACE", "PATCH", "PAINT")));
        inquiryService.grantQualification("S-WIN", new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
        inquiryService.submitBid(inquiryId, "S-WIN", new SubmitBidRequest(
                "bid-" + UUID.randomUUID(), 0, List.of(
                        new BidLineInput(1, "WELD", 100000L),
                        new BidLineInput(2, "REPLACE", 50000L))));
        clock.set(AFTER_DEADLINE);
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-" + UUID.randomUUID(), 1));
        inquiryService.review(inquiryId, new ReviewRequest("rv-a-" + UUID.randomUUID(), "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv-b-" + UUID.randomUUID(), "bob"));
        return caseId;
    }

    private List<Long> itemIds(long caseId) {
        return caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
    }

    private <T> List<T> runConcurrent(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (var task : tasks) {
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
    void completionAndCandidateCreationRaceHasConsistentOrder() throws Exception {
        long caseId = approvedCase("TEMU7000001");
        List<Long> items = itemIds(caseId);

        // 完工与候选建立同时发起，在案件行锁上串行：先到者胜，后到者按新状态拒绝
        List<Object> results = runConcurrent(List.of(
                () -> {
                    try {
                        return caseService.completeRepair(caseId, new CompletionRequest(
                                "cmp-race-" + UUID.randomUUID(), LocalDate.parse("2026-09-21")));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return changeOrderService.create(caseId, new CreateChangeOrderRequest(
                                "co-race-" + UUID.randomUUID(), 0,
                                List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));
                    } catch (ApiException e) {
                        return e;
                    }
                }));

        long completions = results.stream().filter(CompletionView.class::isInstance).count();
        long created = results.stream().filter(ChangeOrderView.class::isInstance).count();
        long rejected = results.stream().filter(ApiException.class::isInstance).count();
        // 恰一方成功，另一方被拒；不能两边都成功（已完工又建候选/建候选又完工）
        assertThat(completions + created).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        if (created == 1) {
            // 候选先建立：完工被拒（409），案件仍 APPROVED 且候选 PENDING
            assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
            assertThat(jdbc.queryForObject(
                    "select count(*) from change_order where case_id = ? and status = 'PENDING'",
                    Integer.class, caseId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from repair_completion where case_id = ?",
                    Integer.class, caseId)).isZero();
        } else {
            // 完工先登记：候选被拒（422），案件 REPAIR_DONE 且无候选
            assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);
            assertThat(jdbc.queryForObject(
                    "select count(*) from change_order where case_id = ?",
                    Integer.class, caseId)).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from repair_completion where case_id = ?",
                    Integer.class, caseId)).isEqualTo(1);
        }
    }

    @Test
    void activationAndQualificationRevocationRaceIsConsistent() throws Exception {
        long caseId = approvedCase("TEMU7000002");
        List<Long> items = itemIds(caseId);
        ChangeOrderView order = changeOrderService.create(caseId, new CreateChangeOrderRequest(
                "co-race2", 0, List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));
        changeOrderService.review(order.id(), new ReviewChangeOrderRequest("rv-first", 1, "carol", "AGREE"));

        // 第二人复核（触发生效）与资格撤销同时发起，在供应商行锁上串行
        List<Object> results = runConcurrent(List.of(
                () -> {
                    try {
                        return changeOrderService.review(order.id(), new ReviewChangeOrderRequest(
                                "rv-second", 1, "dave", "AGREE"));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return inquiryService.revokeQualification("S-WIN",
                                new QualificationRevokeRequest("rev-race", 1, AFTER_DEADLINE.plusSeconds(30)));
                    } catch (ApiException e) {
                        return e;
                    }
                }));

        // 撤销必须成功（追加 REVOKED 版本）
        Integer qualVersion = jdbc.queryForObject(
                "select max(version) from supplier_qualification sq join supplier s on s.id = sq.supplier_id"
                        + " where s.supplier_no = 'S-WIN'", Integer.class);
        assertThat(qualVersion).isEqualTo(2);

        ChangeOrderView finalOrder = changeOrderService.get(order.id());
        boolean activated = results.stream().filter(ChangeOrderView.class::isInstance)
                .map(ChangeOrderView.class::cast)
                .anyMatch(o -> o.status() == ChangeOrderStatus.EFFECTIVE);
        // 全局始终恰有一个 ACTIVE 批复：要么换版成功（新批复），要么保留旧批复
        Integer activeApprovals = jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ? and status = 'ACTIVE'",
                Integer.class, caseId);
        assertThat(activeApprovals).isEqualTo(1);
        if (activated) {
            // 生效先于撤销提交：换版成功，旧批复 SUPERSEDED，资格撤销仅记风险事件
            assertThat(finalOrder.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
            String source = jdbc.queryForObject(
                    "select source from repair_approval where case_id = ? and status = 'ACTIVE'",
                    String.class, caseId);
            assertThat(source).isEqualTo("CHANGE");
        } else {
            // 撤销先于生效提交：最终事务重查资格拒绝，候选仍 PENDING，旧批复保留
            assertThat(finalOrder.status()).isEqualTo(ChangeOrderStatus.PENDING);
            String source = jdbc.queryForObject(
                    "select source from repair_approval where case_id = ? and status = 'ACTIVE'",
                    String.class, caseId);
            assertThat(source).isEqualTo("INQUIRY");
            assertThat(jdbc.queryForObject(
                    "select count(*) from estimate where case_id = ?", Integer.class, caseId)).isEqualTo(1);
        }
    }

    @Test
    void concurrentCandidateCreationOnlyOneAccepted() throws Exception {
        long caseId = approvedCase("TEMU7000003");
        List<Long> items = itemIds(caseId);

        int threads = 5;
        List<java.util.concurrent.Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    return changeOrderService.create(caseId, new CreateChangeOrderRequest(
                            "co-conc-" + UUID.randomUUID(), 0,
                            List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));
                } catch (ApiException e) {
                    return e;
                }
            });
        }
        List<Object> results = runConcurrent(tasks);

        long created = results.stream().filter(ChangeOrderView.class::isInstance).count();
        long rejected = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT
                        || e.status() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        // 每案最多一个活动候选：恰一个受理，其余被序号版本（409）或活动候选（422）拒绝
        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "select count(*) from change_order where case_id = ?", Integer.class, caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from change_order_line", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentReviewsActivateExactlyOnce() throws Exception {
        long caseId = approvedCase("TEMU7000004");
        List<Long> items = itemIds(caseId);
        ChangeOrderView order = changeOrderService.create(caseId, new CreateChangeOrderRequest(
                "co-conc2", 0, List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));

        String[] reviewers = {"carol", "dave", "erin", "frank"};
        List<java.util.concurrent.Callable<Object>> tasks = new ArrayList<>();
        for (String reviewer : reviewers) {
            tasks.add(() -> {
                try {
                    return changeOrderService.review(order.id(), new ReviewChangeOrderRequest(
                            "rv-" + reviewer + "-" + UUID.randomUUID(), 1, reviewer, "AGREE"));
                } catch (ApiException e) {
                    return e;
                }
            });
        }
        List<Object> results = runConcurrent(tasks);

        // 恰两条复核记录、一次换版；其余复核人收到 422（已生效不可再复核）
        assertThat(jdbc.queryForObject(
                "select count(*) from change_order_review where change_order_id = ?",
                Integer.class, order.id())).isEqualTo(2);
        long effective = results.stream().filter(ChangeOrderView.class::isInstance)
                .map(ChangeOrderView.class::cast)
                .filter(o -> o.status() == ChangeOrderStatus.EFFECTIVE).count();
        assertThat(effective).isEqualTo(1);
        long rejected = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(rejected).isEqualTo(reviewers.length - 2);

        ChangeOrderView finalOrder = changeOrderService.get(order.id());
        assertThat(finalOrder.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        assertThat(finalOrder.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ?", Integer.class, caseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ? and status = 'ACTIVE'",
                Integer.class, caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate where case_id = ? and status = 'CURRENT'",
                Integer.class, caseId)).isEqualTo(1);
    }
}
