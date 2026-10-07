package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeDecision;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeReviewRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
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
 * 完工与候选建立竞态一致次序（不能已完工又改批复）、批准与资格撤销竞态一致、
 * 并发复核只切换一次且仅形成一个新批复。
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
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** 构造经密封询价中标批复的修箱案（批复日 UTC 2026-09-21），返回案件 ID */
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
                "csup-" + UUID.randomUUID(), "W001", "中标供应商",
                List.of("WELD", "STRAIGHTEN", "REPLACE", "PATCH")));
        inquiryService.grantQualification("W001", new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
        inquiryService.submitBid(inquiryId, "W001", new SubmitBidRequest("bid-" + UUID.randomUUID(), 0, List.of(
                new BidLineInput(1, "WELD", 100000L),
                new BidLineInput(2, "REPLACE", 50000L))));
        clock.set(AFTER_DEADLINE);
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-" + UUID.randomUUID(), 1));
        inquiryService.review(inquiryId, new ReviewRequest("rv1-" + UUID.randomUUID(), "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv2-" + UUID.randomUUID(), "bob"));
        return caseId;
    }

    private List<Object> runRace(List<java.util.concurrent.Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (var task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
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
        return results;
    }

    @Test
    void completionAndCandidateCreationRaceHasConsistentOrder() throws Exception {
        long caseId = approvedCase("TEMU7000001");
        List<Long> items = caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();

        List<Object> results = runRace(List.of(
                () -> caseService.completeRepair(caseId, new CompletionRequest(
                        "cmp-race-" + UUID.randomUUID(), LocalDate.parse("2026-09-21"))),
                () -> changeOrderService.create(caseId, new CreateChangeOrderRequest(
                        "co-race-" + UUID.randomUUID(), 0, List.of(
                                new ChangeLineInput(items.get(0), "PATCH", 110000L))))));

        long completions = results.stream().filter(CompletionView.class::isInstance).count();
        long candidates = results.stream().filter(ChangeOrderView.class::isInstance).count();
        long rejected = results.stream().filter(ApiException.class::isInstance).count();

        // 一致次序：恰一个成功，另一个被拒绝；不能已完工又建立候选
        assertThat(completions + candidates).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        Integer orderCount = jdbc.queryForObject(
                "select count(*) from change_order where case_id = ?", Integer.class, caseId);
        CaseStatus status = caseService.detail(caseId).caseInfo().status();
        if (completions == 1) {
            // 完工先提交：候选建立被拒绝，案件进入已完工，无变更单
            assertThat(status).isEqualTo(CaseStatus.REPAIR_DONE);
            assertThat(orderCount).isZero();
        } else {
            // 候选先建立：完工被阻断，案件仍为已批复，候选待复核
            assertThat(status).isEqualTo(CaseStatus.APPROVED);
            assertThat(orderCount).isEqualTo(1);
            ChangeOrderView created = results.stream().filter(ChangeOrderView.class::isInstance)
                    .map(ChangeOrderView.class::cast).findFirst().orElseThrow();
            assertThat(created.status()).isEqualTo(ChangeOrderStatus.PENDING);
        }
    }

    @Test
    void approvalSwitchAndQualificationRevokeRaceIsConsistent() throws Exception {
        long caseId = approvedCase("TEMU7000002");
        List<Long> items = caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
        ChangeOrderView created = changeOrderService.create(caseId, new CreateChangeOrderRequest(
                "co-race2", 0, List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));
        changeOrderService.review(created.id(), new ChangeReviewRequest(
                "crv-first", 1, "carol", ChangeDecision.APPROVE));

        List<Object> results = runRace(List.of(
                () -> changeOrderService.review(created.id(), new ChangeReviewRequest(
                        "crv-second-" + UUID.randomUUID(), 2, "dave", ChangeDecision.APPROVE)),
                () -> inquiryService.revokeQualification("W001", new QualificationRevokeRequest(
                        "rev-race-" + UUID.randomUUID(), 1, AFTER_DEADLINE.plusSeconds(30)))));

        // 撤销必须成功（追加 REVOKED 版本）
        long revokeOk = results.stream()
                .filter(com.example.starter.damage.dto.InquiryViews.SupplierView.class::isInstance).count();
        assertThat(revokeOk).isEqualTo(1);
        String qualStatus = jdbc.queryForObject(
                "select status from supplier_qualification sq join supplier s on s.id = sq.supplier_id"
                        + " where s.supplier_no = 'W001' order by version desc limit 1", String.class);
        assertThat(qualStatus).isEqualTo("REVOKED");

        ChangeOrderView current = changeOrderService.get(created.id());
        Integer approvals = jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ?", Integer.class, caseId);
        Integer activeApprovals = jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ? and status = 'ACTIVE'",
                Integer.class, caseId);
        if (current.status() == ChangeOrderStatus.EFFECTIVE) {
            // 切换先于撤销提交：新批复已形成，资格撤销不改写已生效变更
            assertThat(approvals).isEqualTo(2);
            assertThat(activeApprovals).isEqualTo(1);
            assertThat(current.newApprovalId()).isNotNull();
        } else {
            // 撤销先于切换提交：资格重查失败，候选仍待复核，原批复保留
            assertThat(current.status()).isEqualTo(ChangeOrderStatus.PENDING);
            assertThat(approvals).isEqualTo(1);
            assertThat(activeApprovals).isEqualTo(1);
        }
    }

    @Test
    void concurrentSecondReviewsSwitchExactlyOnce() throws Exception {
        long caseId = approvedCase("TEMU7000003");
        List<Long> items = caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
        ChangeOrderView created = changeOrderService.create(caseId, new CreateChangeOrderRequest(
                "co-race3", 0, List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L))));
        changeOrderService.review(created.id(), new ChangeReviewRequest(
                "crv-first3", 1, "carol", ChangeDecision.APPROVE));

        // 四名复核人并发提交第二票（均携带当前版本 2）
        String[] reviewers = {"dave", "erin", "frank", "grace"};
        List<java.util.concurrent.Callable<Object>> tasks = new ArrayList<>();
        for (String reviewer : reviewers) {
            tasks.add(() -> changeOrderService.review(created.id(), new ChangeReviewRequest(
                    "crv-" + reviewer + "-" + UUID.randomUUID(), 2, reviewer, ChangeDecision.APPROVE)));
        }
        List<Object> results = runRace(tasks);

        long switched = results.stream().filter(ChangeOrderView.class::isInstance)
                .map(ChangeOrderView.class::cast)
                .filter(v -> v.status() == ChangeOrderStatus.EFFECTIVE).count();
        long rejected = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(switched).isEqualTo(1);
        assertThat(rejected).isEqualTo(reviewers.length - 1);

        // 恰好两名复核人、一个新估价版本、一个新批复
        Integer reviewRows = jdbc.queryForObject(
                "select count(*) from change_order_review where change_order_id = ?",
                Integer.class, created.id());
        Integer estimates = jdbc.queryForObject(
                "select count(*) from estimate where case_id = ?", Integer.class, caseId);
        Integer approvals = jdbc.queryForObject(
                "select count(*) from repair_approval where case_id = ?", Integer.class, caseId);
        assertThat(reviewRows).isEqualTo(2);
        assertThat(estimates).isEqualTo(2);
        assertThat(approvals).isEqualTo(2);
        assertThat(changeOrderService.get(created.id()).status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
    }
}
