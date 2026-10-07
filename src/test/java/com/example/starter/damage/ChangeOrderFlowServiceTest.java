package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.ReviewChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderLineView;
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
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CloseRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.service.ChangeOrderService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 修理变更单业务流测试：真实 H2（MODE=MySQL）验证候选创建绑定与冻结、跨项/未知工法/
 * 供应商资格拒绝、单活动候选、候选期间完工阻断、双重复核生效换版（旧批复失效）、
 * 逐残损项全损判定、撤回/驳回解除阻断、expectedVersion 与 requestId 幂等、
 * 以及生效后重新完工验收主流程。
 */
@SpringBootTest
@Import(InquiryTestClockConfig.class)
class ChangeOrderFlowServiceTest {

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

    @BeforeEach
    void cleanTables() {
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
    }

    // ---- 构造辅助 ----

    /** 登记 + 检验两项（1000/500，箱值 10000）+ 估价，返回案件 ID */
    private long estimatedRepairCase(String containerNo) {
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

    /**
     * 经密封询价转批复的 APPROVED 案件：中标供应商编号按箱号派生（同用例多案不冲突），
     * 中标价行1 WELD 100000 分、行2 REPLACE 50000 分。
     */
    private long approvedCaseViaInquiry(String containerNo) {
        long caseId = estimatedRepairCase(containerNo);
        List<Long> items = itemIds(caseId);
        clock.set(T0); // 同一用例内重复构造时复位时钟，保证截止时刻有效
        String supplierNo = "S-" + containerNo;
        long inquiryId = inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                "pub-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "STRAIGHTEN", "PATCH")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE", "PATCH"))))).id();
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-" + UUID.randomUUID(), supplierNo, "中标供应商",
                List.of("WELD", "STRAIGHTEN", "REPLACE", "PATCH", "PAINT")));
        inquiryService.grantQualification(supplierNo, new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
        inquiryService.submitBid(inquiryId, supplierNo, new SubmitBidRequest(
                "bid-" + UUID.randomUUID(), 0, List.of(
                        new BidLineInput(1, "WELD", 100000L),
                        new BidLineInput(2, "REPLACE", 50000L))));
        clock.set(AFTER_DEADLINE);
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-" + UUID.randomUUID(), 1));
        inquiryService.review(inquiryId, new ReviewRequest("rv-a-" + UUID.randomUUID(), "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv-b-" + UUID.randomUUID(), "bob"));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        return caseId;
    }

    private ChangeOrderView createOrder(long caseId, int expectedVersion, String requestId,
                                        ChangeLineInput... lines) {
        return changeOrderService.create(caseId, new CreateChangeOrderRequest(
                requestId, expectedVersion, List.of(lines)));
    }

    private ChangeOrderView agree(long orderId, int expectedVersion, String reviewer) {
        return changeOrderService.review(orderId, new ReviewChangeOrderRequest(
                "rv-" + reviewer + "-" + UUID.randomUUID(), expectedVersion, reviewer, "AGREE"));
    }

    // ---- 主流程：双重复核生效换版后重新完工验收 ----

    @Test
    void changeOrderEffectiveThenCompleteAndAcceptanceWithNewApproval() {
        long caseId = approvedCaseViaInquiry("TEMU6000001");
        List<Long> items = itemIds(caseId);
        CaseDetailView before = caseService.detail(caseId);
        long oldApprovalId = before.approvals().get(0).id();
        long oldEstimateId = before.estimates().stream()
                .filter(e -> e.status().equals("CURRENT")).findFirst().orElseThrow().id();

        // 创建候选：行1 改工法 PATCH、新价 120000 分；行2 沿用原中标
        ChangeOrderView created = createOrder(caseId, 0, "co-1",
                new ChangeLineInput(items.get(0), "PATCH", 120000L));
        assertThat(created.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.seq()).isEqualTo(1);
        assertThat(created.approvalId()).isEqualTo(oldApprovalId);
        assertThat(created.estimateId()).isEqualTo(oldEstimateId);
        assertThat(created.supplierNo()).isEqualTo("S-TEMU6000001");
        assertThat(created.qualificationVersion()).isEqualTo(1);
        assertThat(created.oldTotalCents()).isEqualTo(150000);
        assertThat(created.newTotalCents()).isEqualTo(170000);
        assertThat(created.lines()).hasSize(2);
        ChangeOrderLineView changedLine = created.lines().stream()
                .filter(ChangeOrderLineView::changed).findFirst().orElseThrow();
        assertThat(changedLine.damageItemId()).isEqualTo(items.get(0));
        assertThat(changedLine.oldMethodCode()).isEqualTo("WELD");
        assertThat(changedLine.oldAmountCents()).isEqualTo(100000);
        assertThat(changedLine.newMethodCode()).isEqualTo("PATCH");
        assertThat(changedLine.newAmountCents()).isEqualTo(120000);
        ChangeOrderLineView keptLine = created.lines().stream()
                .filter(l -> !l.changed()).findFirst().orElseThrow();
        assertThat(keptLine.newMethodCode()).isEqualTo("REPLACE");
        assertThat(keptLine.newAmountCents()).isEqualTo(50000);

        // 候选期间原完工入口拒绝，不能用旧批复绕行
        assertThatThrownBy(() -> caseService.completeRepair(caseId,
                new CompletionRequest("cmp-blocked", LocalDate.parse("2026-09-21"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("变更候选");

        // 单人复核不切换：仍为 PENDING，旧批复仍有效
        ChangeOrderView afterFirst = agree(created.id(), 1, "carol");
        assertThat(afterFirst.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(afterFirst.version()).isEqualTo(1);
        assertThat(afterFirst.newApprovalId()).isNull();
        CaseDetailView mid = caseService.detail(caseId);
        assertThat(mid.approvals()).filteredOn(a -> a.status().equals("ACTIVE")).singleElement()
                .satisfies(a -> assertThat(a.id()).isEqualTo(oldApprovalId));

        // 第二名不同复核人同意 → 生效换版
        ChangeOrderView effective = agree(created.id(), 1, "dave");
        assertThat(effective.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        assertThat(effective.version()).isEqualTo(2);
        assertThat(effective.newEstimateId()).isNotNull();
        assertThat(effective.newApprovalId()).isNotNull();

        CaseDetailView after = caseService.detail(caseId);
        assertThat(after.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(after.caseInfo().determination()).isEqualTo(Determination.REPAIR);
        // 新估价版本：总额 1700.00（170000 分），旧版本 SUPERSEDED 保留
        assertThat(after.estimates()).hasSize(2);
        assertThat(after.estimates().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(after.estimates().get(1).status()).isEqualTo("CURRENT");
        assertThat(after.estimates().get(1).totalCost()).isEqualByComparingTo("1700.00");
        assertThat(after.estimates().get(1).determination()).isEqualTo(Determination.REPAIR);
        // 旧批复失效（旧复检资格失效），新批复 ACTIVE 且来源 CHANGE、沿用原开标
        assertThat(after.approvals()).hasSize(2);
        assertThat(after.approvals().get(0).status()).isEqualTo("SUPERSEDED");
        var newApproval = after.approvals().get(1);
        assertThat(newApproval.status()).isEqualTo("ACTIVE");
        assertThat(newApproval.source()).isEqualTo("CHANGE");
        assertThat(newApproval.openingId()).isEqualTo(after.approvals().get(0).openingId());
        // 原询价与报价不覆盖
        assertThat(jdbc.queryForObject("select count(*) from bid_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from inquiry where case_id = ?", String.class, caseId)).isEqualTo("CONVERTED");

        // 生效后不可撤回、不可再复核
        assertThatThrownBy(() -> changeOrderService.withdraw(created.id(),
                new WithdrawChangeOrderRequest("wd-late", 2)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> agree(created.id(), 2, "erin"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 重新完工验收：按新批复有效期完工 → 复检合格 → 放行结案
        caseService.completeRepair(caseId, new CompletionRequest("cmp-1", LocalDate.parse("2026-09-22")));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);
        caseService.reinspect(caseId, new ReinspectionRequest("ri-1", true));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_PASSED);
        caseService.close(caseId, new CloseRequest("cl-1"));
        CaseDetailView closed = caseService.detail(caseId);
        assertThat(closed.caseInfo().status()).isEqualTo(CaseStatus.RELEASED);
        assertThat(closed.caseInfo().closedAt()).isNotNull();
    }

    // ---- 创建校验：跨案项 / 未知工法 / 供应商资格 / 状态前置 ----

    @Test
    void crossCaseItemUnknownMethodAndSupplierMethodRejected() {
        long caseId = approvedCaseViaInquiry("TEMU6000002");
        long otherCase = approvedCaseViaInquiry("TEMU6000003");
        List<Long> items = itemIds(caseId);
        List<Long> otherItems = itemIds(otherCase);

        // 跨案残损项 → 400
        assertThatThrownBy(() -> createOrder(caseId, 0, "co-cross",
                new ChangeLineInput(otherItems.get(0), "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("不属于该案件");

        // 未知工法 → 400
        assertThatThrownBy(() -> createOrder(caseId, 0, "co-unknown",
                new ChangeLineInput(items.get(0), "NO_SUCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("未知修箱工法");

        // 重复残损项 → 400
        assertThatThrownBy(() -> createOrder(caseId, 0, "co-dup",
                new ChangeLineInput(items.get(0), "PATCH", 1000L),
                new ChangeLineInput(items.get(0), "PATCH", 2000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("不得重复");

        // 非正分报价 → 400
        assertThatThrownBy(() -> createOrder(caseId, 0, "co-zero",
                new ChangeLineInput(items.get(0), "PATCH", 0L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 全部校验失败均不占候选：仍可正常创建
        ChangeOrderView ok = createOrder(caseId, 0, "co-ok",
                new ChangeLineInput(items.get(0), "PATCH", 1000L));
        assertThat(ok.status()).isEqualTo(ChangeOrderStatus.PENDING);
    }

    @Test
    void supplierWithoutMethodQualificationRejected() {
        // 中标供应商仅会 WELD/REPLACE，变更要求 PATCH → 422
        long caseId = estimatedRepairCase("TEMU6000004");
        List<Long> items = itemIds(caseId);
        long inquiryId = inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                "pub-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "PATCH")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE"))))).id();
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-narrow", "S-NARROW", "有限工法供应商", List.of("WELD", "REPLACE")));
        inquiryService.grantQualification("S-NARROW", new QualificationGrantRequest(
                "q-narrow", 0, T0.minus(Duration.ofDays(30)), null));
        inquiryService.submitBid(inquiryId, "S-NARROW", new SubmitBidRequest(
                "bid-narrow", 0, List.of(
                        new BidLineInput(1, "WELD", 100000L),
                        new BidLineInput(2, "REPLACE", 50000L))));
        clock.set(AFTER_DEADLINE);
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-narrow", 1));
        inquiryService.review(inquiryId, new ReviewRequest("rv-n1", "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv-n2", "bob"));

        assertThatThrownBy(() -> createOrder(caseId, 0, "co-noqual",
                new ChangeLineInput(items.get(0), "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("不具备工法");
    }

    @Test
    void createRequiresApprovedUnfinishedCaseAndInquiryApproval() {
        // 已估价未批复 → 422
        long estimated = estimatedRepairCase("TEMU6000005");
        List<Long> items = itemIds(estimated);
        assertThatThrownBy(() -> createOrder(estimated, 0, "co-est",
                new ChangeLineInput(items.get(0), "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 旧入口 DIRECT 批复（无中标供应商）→ 422
        caseService.approve(estimated, new ApprovalRequest("ap-direct", LocalDate.parse("2026-09-21")));
        assertThatThrownBy(() -> createOrder(estimated, 0, "co-direct",
                new ChangeLineInput(items.get(0), "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("中标批复");

        // 已整案完工（REPAIR_DONE）→ 422
        long approved = approvedCaseViaInquiry("TEMU6000006");
        caseService.completeRepair(approved, new CompletionRequest("cmp-x", LocalDate.parse("2026-09-21")));
        List<Long> approvedItems = itemIds(approved);
        assertThatThrownBy(() -> createOrder(approved, 0, "co-done",
                new ChangeLineInput(approvedItems.get(0), "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 不存在案件 → 404
        assertThatThrownBy(() -> createOrder(999999, 0, "co-404",
                new ChangeLineInput(1L, "PATCH", 1000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- 单活动候选与撤回 ----

    @Test
    void singleActiveCandidateAndWithdrawReleasesCompletionBlock() {
        long caseId = approvedCaseViaInquiry("TEMU6000007");
        List<Long> items = itemIds(caseId);
        long oldApprovalId = caseService.detail(caseId).approvals().get(0).id();

        ChangeOrderView first = createOrder(caseId, 0, "co-a",
                new ChangeLineInput(items.get(0), "PATCH", 110000L));
        // 活动候选期间再建 → 422；序号期望错位 → 409
        assertThatThrownBy(() -> createOrder(caseId, 1, "co-b",
                new ChangeLineInput(items.get(1), "PATCH", 60000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 撤回：解除完工阻断，保留原有效批复
        ChangeOrderView withdrawn = changeOrderService.withdraw(first.id(),
                new WithdrawChangeOrderRequest("wd-1", 1));
        assertThat(withdrawn.status()).isEqualTo(ChangeOrderStatus.WITHDRAWN);
        assertThat(withdrawn.version()).isEqualTo(2);
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.approvals()).filteredOn(a -> a.status().equals("ACTIVE")).singleElement()
                .satisfies(a -> assertThat(a.id()).isEqualTo(oldApprovalId));
        caseService.completeRepair(caseId, new CompletionRequest("cmp-after-wd", LocalDate.parse("2026-09-21")));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);

        // 撤回后可再建新候选（序号递增）；但本案已完工 → 422
        assertThatThrownBy(() -> createOrder(caseId, 1, "co-c",
                new ChangeLineInput(items.get(0), "PATCH", 110000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void withdrawVersionConflictReturns409() {
        long caseId = approvedCaseViaInquiry("TEMU6000008");
        List<Long> items = itemIds(caseId);
        ChangeOrderView order = createOrder(caseId, 0, "co-v",
                new ChangeLineInput(items.get(0), "PATCH", 110000L));
        assertThatThrownBy(() -> changeOrderService.withdraw(order.id(),
                new WithdrawChangeOrderRequest("wd-bad", 9)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // 创建期望序号错位 → 409
        assertThatThrownBy(() -> createOrder(caseId, 5, "co-v2",
                new ChangeLineInput(items.get(0), "PATCH", 110000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---- 驳回 ----

    @Test
    void rejectDecisionRejectsCandidateAndKeepsOriginalApproval() {
        long caseId = approvedCaseViaInquiry("TEMU6000009");
        List<Long> items = itemIds(caseId);
        long oldApprovalId = caseService.detail(caseId).approvals().get(0).id();
        ChangeOrderView order = createOrder(caseId, 0, "co-rj",
                new ChangeLineInput(items.get(0), "PATCH", 110000L));

        ChangeOrderView rejected = changeOrderService.review(order.id(), new ReviewChangeOrderRequest(
                "rv-rej", 1, "carol", "REJECT"));
        assertThat(rejected.status()).isEqualTo(ChangeOrderStatus.REJECTED);
        assertThat(rejected.version()).isEqualTo(2);
        assertThat(rejected.reviews()).singleElement()
                .satisfies(r -> assertThat(r.decision()).isEqualTo("REJECT"));

        // 驳回解除完工阻断，原批复保留
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.approvals()).filteredOn(a -> a.status().equals("ACTIVE")).singleElement()
                .satisfies(a -> assertThat(a.id()).isEqualTo(oldApprovalId));
        caseService.completeRepair(caseId, new CompletionRequest("cmp-rej", LocalDate.parse("2026-09-21")));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);

        // 非法决定 → 400（另起一案，避免本案已完工）
        long caseId2 = approvedCaseViaInquiry("TEMU6000015");
        List<Long> items2 = itemIds(caseId2);
        ChangeOrderView order2 = createOrder(caseId2, 0, "co-rj2",
                new ChangeLineInput(items2.get(0), "PATCH", 110000L));
        assertThatThrownBy(() -> changeOrderService.review(order2.id(),
                new ReviewChangeOrderRequest("rv-bad-dec", 1, "carol", "MAYBE")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- 逐残损项全损判定 ----

    @Test
    void perItemTotalLossRuleBlocksActivationButSumAboveThresholdIsFine() {
        // 箱值 10000，阈值 3000.00 = 300000 分
        long caseId = approvedCaseViaInquiry("TEMU6000010");
        List<Long> items = itemIds(caseId);

        // 单项 300001 分 > 阈值 → 生效拒绝，候选仍 PENDING，无新估价/批复
        ChangeOrderView loss = createOrder(caseId, 0, "co-tl",
                new ChangeLineInput(items.get(0), "PATCH", 300001L));
        agree(loss.id(), 1, "carol");
        assertThatThrownBy(() -> agree(loss.id(), 1, "dave"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("全损");
        ChangeOrderView stillPending = changeOrderService.get(loss.id());
        assertThat(stillPending.status()).isEqualTo(ChangeOrderStatus.PENDING);
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.estimates()).hasSize(1);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).status()).isEqualTo("ACTIVE");
        // 全损候选可撤回
        changeOrderService.withdraw(loss.id(), new WithdrawChangeOrderRequest("wd-tl", 1));

        // 两项各 200000 分（各 2000.00 ≤ 3000），合计 400000 分超阈值但不误判：生效成功
        ChangeOrderView ok = createOrder(caseId, 1, "co-sum",
                new ChangeLineInput(items.get(0), "PATCH", 200000L),
                new ChangeLineInput(items.get(1), "PATCH", 200000L));
        agree(ok.id(), 1, "carol");
        ChangeOrderView effective = agree(ok.id(), 1, "dave");
        assertThat(effective.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        CaseDetailView after = caseService.detail(caseId);
        assertThat(after.estimates()).hasSize(2);
        assertThat(after.estimates().get(1).determination()).isEqualTo(Determination.REPAIR);
        assertThat(after.estimates().get(1).totalCost()).isEqualByComparingTo("4000.00");
    }

    // ---- 生效前资格重查 ----

    @Test
    void activationRechecksQualificationAndRejectsWhenRevoked() {
        long caseId = approvedCaseViaInquiry("TEMU6000011");
        List<Long> items = itemIds(caseId);
        ChangeOrderView order = createOrder(caseId, 0, "co-qual",
                new ChangeLineInput(items.get(0), "PATCH", 110000L));
        agree(order.id(), 1, "carol");

        // 候选期间资格被撤销（批复后撤销仅记风险事件，不改历史中标证据）
        inquiryService.revokeQualification("S-TEMU6000011", new QualificationRevokeRequest(
                "rev-win", 1, AFTER_DEADLINE.plusSeconds(30)));

        // 最终事务重查资格：已撤销 → 生效拒绝，候选仍 PENDING，批复不换版
        assertThatThrownBy(() -> agree(order.id(), 1, "dave"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("资格已失效");
        assertThat(changeOrderService.get(order.id()).status()).isEqualTo(ChangeOrderStatus.PENDING);
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).status()).isEqualTo("ACTIVE");
        assertThat(detail.events()).extracting(e -> e.eventType()).contains("QUALIFICATION_RISK");
    }

    // ---- 复核约束 ----

    @Test
    void sameReviewerTwiceRejectedAndReviewVersionConflict() {
        long caseId = approvedCaseViaInquiry("TEMU6000012");
        List<Long> items = itemIds(caseId);
        ChangeOrderView order = createOrder(caseId, 0, "co-rv",
                new ChangeLineInput(items.get(0), "PATCH", 110000L));

        agree(order.id(), 1, "carol");
        assertThatThrownBy(() -> agree(order.id(), 1, "carol"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("重复复核");
        // 复核版本错位 → 409（版本在生效/驳回/撤回时才 +1，此处仍为 1）
        assertThatThrownBy(() -> agree(order.id(), 7, "dave"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // 未知变更单 → 404
        assertThatThrownBy(() -> agree(999999, 1, "dave"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- 幂等 ----

    @Test
    void sameKeySameParamsReplayAndDifferentParams409() {
        long caseId = approvedCaseViaInquiry("TEMU6000013");
        List<Long> items = itemIds(caseId);
        CreateChangeOrderRequest req = new CreateChangeOrderRequest("co-idem", 0,
                List.of(new ChangeLineInput(items.get(0), "PATCH", 110000L)));
        ChangeOrderView first = changeOrderService.create(caseId, req);
        ChangeOrderView replay = changeOrderService.create(caseId, req);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from change_order where case_id = ?", Integer.class, caseId)).isEqualTo(1);

        // 同键异参 → 409，不新增
        CreateChangeOrderRequest other = new CreateChangeOrderRequest("co-idem", 0,
                List.of(new ChangeLineInput(items.get(0), "PATCH", 120000L)));
        assertThatThrownBy(() -> changeOrderService.create(caseId, other))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject(
                "select count(*) from change_order where case_id = ?", Integer.class, caseId)).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        long caseId = approvedCaseViaInquiry("TEMU6000014");
        List<Long> items = itemIds(caseId);
        String key = "co-fail-key";
        // 首次使用该键：未知工法失败
        assertThatThrownBy(() -> changeOrderService.create(caseId, new CreateChangeOrderRequest(
                key, 0, List.of(new ChangeLineInput(items.get(0), "NO_SUCH", 1000L)))))
                .isInstanceOf(ApiException.class);
        // 失败不占键：同键修正参数后可成功
        ChangeOrderView ok = changeOrderService.create(caseId, new CreateChangeOrderRequest(
                key, 0, List.of(new ChangeLineInput(items.get(0), "PATCH", 1000L))));
        assertThat(ok.status()).isEqualTo(ChangeOrderStatus.PENDING);
    }
}
