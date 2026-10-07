package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.ChangeDecision;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeReviewRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderLineView;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import com.example.starter.damage.dto.InquiryRequests.BidLineInput;
import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.InquiryItemSpec;
import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
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
 * 修理变更单业务流测试：真实 H2（MODE=MySQL）验证候选申请冻结快照、跨案/未知工法/供应商资格拒绝、
 * 每案一个活动候选、候选期间完工阻断、双复核切换、逐残损项全损判定、撤回/驳回解除阻断、
 * expectedVersion 乐观锁、requestId 幂等与中标证据保留。
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

    private List<Long> itemIds(long caseId) {
        return caseService.detail(caseId).items().stream().map(DamageItemView::id).toList();
    }

    /**
     * 构造经密封询价中标批复的修箱案：箱值 10000.00，两项残损（DN 1000.00 / BR 500.00），
     * 中标报价行1 WELD 100000 分、行2 REPLACE 50000 分；批复日期为 UTC 2026-09-21。
     */
    private long approvedCaseViaInquiry(String containerNo) {
        clock.set(T0); // 可重复构造：回到截止前，结束时钟停在 AFTER_DEADLINE
        String supplierNo = "W" + containerNo.substring(4); // 每案一个唯一中标供应商
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("500.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        List<Long> items = itemIds(caseId);
        long inquiryId = inquiryService.publishInquiry(caseId, new PublishInquiryRequest(
                "pub-" + UUID.randomUUID(), 0, DEADLINE, List.of(
                        new InquiryItemSpec(items.get(0), List.of("WELD", "STRAIGHTEN", "PATCH")),
                        new InquiryItemSpec(items.get(1), List.of("WELD", "REPLACE"))))).id();
        inquiryService.createSupplier(new CreateSupplierRequest(
                "csup-" + UUID.randomUUID(), supplierNo, "中标供应商",
                List.of("WELD", "STRAIGHTEN", "REPLACE", "PATCH")));
        inquiryService.grantQualification(supplierNo, new QualificationGrantRequest(
                "q-" + UUID.randomUUID(), 0, T0.minus(Duration.ofDays(30)), null));
        inquiryService.submitBid(inquiryId, supplierNo, new SubmitBidRequest("bid-" + UUID.randomUUID(), 0, List.of(
                new BidLineInput(1, "WELD", 100000L),
                new BidLineInput(2, "REPLACE", 50000L))));
        clock.set(AFTER_DEADLINE);
        inquiryService.openBids(inquiryId, new OpenBidsRequest("open-" + UUID.randomUUID(), 1));
        inquiryService.review(inquiryId, new ReviewRequest("rv1-" + UUID.randomUUID(), "alice"));
        inquiryService.review(inquiryId, new ReviewRequest("rv2-" + UUID.randomUUID(), "bob"));
        return caseId;
    }

    private ChangeOrderView createChange(long caseId, String requestId, int expectedVersion,
                                         List<ChangeLineInput> lines) {
        return changeOrderService.create(caseId, new CreateChangeOrderRequest(requestId, expectedVersion, lines));
    }

    private ChangeOrderView approve(long changeOrderId, String reviewer, int expectedVersion) {
        return changeOrderService.review(changeOrderId, new ChangeReviewRequest(
                "crv-" + reviewer + "-" + UUID.randomUUID(), expectedVersion, reviewer, ChangeDecision.APPROVE));
    }

    // ---- 主流程：申请冻结快照并阻断完工，双复核切换后重新完工验收 ----

    @Test
    void createFreezesSnapshotBlocksCompletionAndSwitchFormsNewEstimateAndApproval() {
        long caseId = approvedCaseViaInquiry("TEMU6000001");
        List<Long> items = itemIds(caseId);

        ChangeOrderView created = createChange(caseId, "co-1", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 120000L)));
        assertThat(created.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.supplierNo()).isEqualTo("W6000001");
        assertThat(created.qualificationVersion()).isEqualTo(1);
        assertThat(created.originalTotalCents()).isEqualTo(150000);
        assertThat(created.newTotalCents()).isEqualTo(170000);
        assertThat(created.newEstimateId()).isNull();
        assertThat(created.newApprovalId()).isNull();
        assertThat(created.lines()).hasSize(2);
        ChangeOrderLineView changed = created.lines().stream().filter(ChangeOrderLineView::changed).findFirst().orElseThrow();
        assertThat(changed.damageItemId()).isEqualTo(items.get(0));
        assertThat(changed.beforeMethod()).isEqualTo("WELD");
        assertThat(changed.beforeAmountCents()).isEqualTo(100000);
        assertThat(changed.afterMethod()).isEqualTo("PATCH");
        assertThat(changed.afterAmountCents()).isEqualTo(120000);
        ChangeOrderLineView kept = created.lines().stream().filter(l -> !l.changed()).findFirst().orElseThrow();
        assertThat(kept.beforeMethod()).isEqualTo("REPLACE");
        assertThat(kept.afterMethod()).isEqualTo("REPLACE");
        assertThat(kept.afterAmountCents()).isEqualTo(50000);

        // 候选期间原完工入口拒绝，不能用旧批复绕行
        assertThatThrownBy(() -> caseService.completeRepair(caseId,
                new CompletionRequest("cmp-blocked", LocalDate.parse("2026-09-21"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("变更候选");

        // 第一人同意不切换
        ChangeOrderView afterFirst = approve(created.id(), "carol", 1);
        assertThat(afterFirst.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(afterFirst.version()).isEqualTo(2);
        assertThat(caseService.detail(caseId).approvals()).hasSize(1);

        // 第二名不同复核人同意 → 切换新估价与批复
        ChangeOrderView effective = approve(created.id(), "dave", 2);
        assertThat(effective.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        assertThat(effective.version()).isEqualTo(3);
        assertThat(effective.newEstimateId()).isNotNull();
        assertThat(effective.newApprovalId()).isNotNull();
        assertThat(effective.decidedAt()).isNotNull();

        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.estimates()).hasSize(2);
        assertThat(detail.estimates().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(detail.estimates().get(1).status()).isEqualTo("CURRENT");
        assertThat(detail.estimates().get(1).totalCost()).isEqualByComparingTo("1700.00");
        assertThat(detail.estimates().get(1).determination()).isEqualTo(Determination.REPAIR);
        assertThat(detail.approvals()).hasSize(2);
        assertThat(detail.approvals().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(detail.approvals().get(0).source()).isEqualTo("INQUIRY");
        assertThat(detail.approvals().get(1).status()).isEqualTo("ACTIVE");
        assertThat(detail.approvals().get(1).source()).isEqualTo("CHANGE");
        assertThat(detail.approvals().get(1).approvalDate()).isEqualTo(LocalDate.parse("2026-09-21"));

        // 生效后不可撤回
        assertThatThrownBy(() -> changeOrderService.withdraw(created.id(),
                new WithdrawChangeOrderRequest("wd-late", 3)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 报价改版后重新完工验收：完工 → 复检合格 → 放行结案
        caseService.completeRepair(caseId, new CompletionRequest("cmp-after", LocalDate.parse("2026-09-21")));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);
    }

    // ---- 跨案项、未知工法、供应商资格拒绝 ----

    @Test
    void crossCaseItemUnknownMethodAndUnqualifiedSupplierRejected() {
        long caseId = approvedCaseViaInquiry("TEMU6000002");
        long otherCaseId = approvedCaseViaInquiry("TEMU6000003");
        List<Long> items = itemIds(caseId);
        List<Long> otherItems = itemIds(otherCaseId);

        // 跨案残损项 → 400
        assertThatThrownBy(() -> createChange(caseId, "co-cross", 0, List.of(
                new ChangeLineInput(otherItems.get(0), "PATCH", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("跨案");

        // 未知工法 → 400
        assertThatThrownBy(() -> createChange(caseId, "co-unknown", 0, List.of(
                new ChangeLineInput(items.get(0), "NOT_A_METHOD", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("未知修箱工法");

        // 工法已知但中标供应商不具对应资格（PAINT 不在其适用工法内）→ 422
        assertThatThrownBy(() -> createChange(caseId, "co-unqualified", 0, List.of(
                new ChangeLineInput(items.get(0), "PAINT", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("不具对应资格");

        // 变更项数量超 10 → 400（11 个不同残损项 id，均属本案校验前触发数量上限）
        assertThatThrownBy(() -> changeOrderService.create(caseId, new CreateChangeOrderRequest(
                "co-too-many", 0,
                java.util.stream.LongStream.range(0, 11)
                        .mapToObj(i -> new ChangeLineInput(items.get(0) + i, "PATCH", 1000L))
                        .toList())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 全部拒绝后本案仍无变更单
        assertThat(changeOrderService.listByCase(caseId)).isEmpty();
    }

    // ---- 每案最多一个活动候选 ----

    @Test
    void onlyOneActiveCandidatePerCase() {
        long caseId = approvedCaseViaInquiry("TEMU6000004");
        List<Long> items = itemIds(caseId);
        ChangeOrderView first = createChange(caseId, "co-a", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 110000L)));

        // 正确的期望数量但已有活动候选 → 422
        assertThatThrownBy(() -> createChange(caseId, "co-b", 1, List.of(
                new ChangeLineInput(items.get(1), "WELD", 60000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("活动候选");

        // 撤回后可再申请（期望数量 1）
        changeOrderService.withdraw(first.id(), new WithdrawChangeOrderRequest("wd-a", 1));
        ChangeOrderView second = createChange(caseId, "co-c", 1, List.of(
                new ChangeLineInput(items.get(1), "WELD", 60000L)));
        assertThat(second.status()).isEqualTo(ChangeOrderStatus.PENDING);
    }

    // ---- 撤回与驳回解除完工阻断 ----

    @Test
    void withdrawAndRejectReleaseCompletionBlockAndKeepOriginalApproval() {
        long caseId = approvedCaseViaInquiry("TEMU6000005");
        List<Long> items = itemIds(caseId);

        ChangeOrderView withdrawn = createChange(caseId, "co-w", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 110000L)));
        ChangeOrderView wd = changeOrderService.withdraw(withdrawn.id(),
                new WithdrawChangeOrderRequest("wd-1", 1));
        assertThat(wd.status()).isEqualTo(ChangeOrderStatus.WITHDRAWN);
        assertThat(wd.version()).isEqualTo(2);
        assertThat(wd.decidedAt()).isNotNull();
        // 阻断解除，原批复仍可完工
        caseService.completeRepair(caseId, new CompletionRequest("cmp-w", LocalDate.parse("2026-09-21")));
        assertThat(caseService.detail(caseId).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);

        // 驳回路径：另起一案
        long caseId2 = approvedCaseViaInquiry("TEMU6000006");
        List<Long> items2 = itemIds(caseId2);
        ChangeOrderView rejected = createChange(caseId2, "co-r", 0, List.of(
                new ChangeLineInput(items2.get(0), "PATCH", 110000L)));
        ChangeOrderView rj = changeOrderService.review(rejected.id(), new ChangeReviewRequest(
                "crv-rej", 1, "carol", ChangeDecision.REJECT));
        assertThat(rj.status()).isEqualTo(ChangeOrderStatus.REJECTED);
        assertThat(rj.version()).isEqualTo(2);
        // 原批复保留且可完工
        CaseDetailView detail = caseService.detail(caseId2);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).status()).isEqualTo("ACTIVE");
        caseService.completeRepair(caseId2, new CompletionRequest("cmp-r", LocalDate.parse("2026-09-21")));
        assertThat(caseService.detail(caseId2).caseInfo().status()).isEqualTo(CaseStatus.REPAIR_DONE);

        // 已撤回/已驳回不能再复核
        assertThatThrownBy(() -> approve(withdrawn.id(), "dave", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ---- 逐残损项全损判定 ----

    @Test
    void perItemTotalLossRuleBlocksSwitchButCaseTotalIsNotItemThreshold() {
        // 单项超 30%（3000.01 元 = 300001 分 > 箱值 10000 的 30%）→ 不得转修理批复
        long caseId = approvedCaseViaInquiry("TEMU6000007");
        List<Long> items = itemIds(caseId);
        ChangeOrderView tl = createChange(caseId, "co-tl", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 300001L)));
        approve(tl.id(), "carol", 1);
        assertThatThrownBy(() -> approve(tl.id(), "dave", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("全损");
        // 切换失败候选仍待复核，可撤回；原批复保留
        assertThat(changeOrderService.get(tl.id()).status()).isEqualTo(ChangeOrderStatus.PENDING);
        changeOrderService.withdraw(tl.id(), new WithdrawChangeOrderRequest("wd-tl", 2));
        assertThat(caseService.detail(caseId).approvals()).hasSize(1);
        assertThat(caseService.detail(caseId).approvals().get(0).status()).isEqualTo("ACTIVE");

        // 恰 30%（3000.00 元 = 300000 分）按修理处理 → 可切换
        long caseId2 = approvedCaseViaInquiry("TEMU6000008");
        List<Long> items2 = itemIds(caseId2);
        ChangeOrderView edge = createChange(caseId2, "co-edge", 0, List.of(
                new ChangeLineInput(items2.get(0), "PATCH", 300000L)));
        approve(edge.id(), "carol", 1);
        ChangeOrderView edgeDone = approve(edge.id(), "dave", 2);
        assertThat(edgeDone.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        assertThat(caseService.detail(caseId2).estimates().get(1).determination()).isEqualTo(Determination.REPAIR);

        // 全案合计超 30% 但每项均不超（2000+2000=4000 > 3000）→ 不误判全损，可切换
        long caseId3 = approvedCaseViaInquiry("TEMU6000009");
        List<Long> items3 = itemIds(caseId3);
        ChangeOrderView sum = createChange(caseId3, "co-sum", 0, List.of(
                new ChangeLineInput(items3.get(0), "PATCH", 200000L),
                new ChangeLineInput(items3.get(1), "WELD", 200000L)));
        approve(sum.id(), "carol", 1);
        ChangeOrderView sumDone = approve(sum.id(), "dave", 2);
        assertThat(sumDone.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
        CaseDetailView detail3 = caseService.detail(caseId3);
        assertThat(detail3.estimates().get(1).totalCost()).isEqualByComparingTo("4000.00");
        assertThat(detail3.estimates().get(1).determination()).isEqualTo(Determination.REPAIR);
    }

    // ---- 切换时资格失效拒绝 ----

    @Test
    void switchRejectedWhenQualificationRevokedBeforeSecondReview() {
        long caseId = approvedCaseViaInquiry("TEMU6000010");
        List<Long> items = itemIds(caseId);
        ChangeOrderView created = createChange(caseId, "co-q", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 110000L)));
        approve(created.id(), "carol", 1);

        // 第一人同意后撤销中标供应商资格
        inquiryService.revokeQualification("W6000010", new com.example.starter.damage.dto.InquiryRequests
                .QualificationRevokeRequest("rev-q", 1, AFTER_DEADLINE.plusSeconds(30)));

        // 第二名复核人同意时最终事务重查资格 → 拒绝切换
        assertThatThrownBy(() -> approve(created.id(), "dave", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("资格");
        assertThat(changeOrderService.get(created.id()).status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(caseService.detail(caseId).approvals()).hasSize(1);
    }

    // ---- 前置状态守卫 ----

    @Test
    void createOnlyForInquiryApprovedNotCompletedCase() {
        // 未批复（ESTIMATED）→ 422
        long estimated = caseService.register(new RegisterRequest(
                "reg-e", "TEMU6000011", new BigDecimal("10000.00"))).id();
        caseService.survey(estimated, new SurveyRequest("svy-e", List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")))));
        caseService.estimate(estimated, new EstimateRequest("est-e"));
        long estimatedItem = itemIds(estimated).get(0);
        assertThatThrownBy(() -> createChange(estimated, "co-e", 0, List.of(
                new ChangeLineInput(estimatedItem, "PATCH", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // DIRECT 旧入口批复（无中标供应商）→ 422
        long direct = caseService.register(new RegisterRequest(
                "reg-d", "TEMU6000012", new BigDecimal("10000.00"))).id();
        caseService.survey(direct, new SurveyRequest("svy-d", List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")))));
        caseService.estimate(direct, new EstimateRequest("est-d"));
        caseService.approve(direct, new ApprovalRequest("ap-d", LocalDate.parse("2026-09-20")));
        long directItem = itemIds(direct).get(0);
        assertThatThrownBy(() -> createChange(direct, "co-d", 0, List.of(
                new ChangeLineInput(directItem, "PATCH", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("中标批复");

        // 已完工（REPAIR_DONE）→ 422
        long done = approvedCaseViaInquiry("TEMU6000013");
        caseService.completeRepair(done, new CompletionRequest("cmp-done", LocalDate.parse("2026-09-21")));
        long doneItem = itemIds(done).get(0);
        assertThatThrownBy(() -> createChange(done, "co-done", 0, List.of(
                new ChangeLineInput(doneItem, "PATCH", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ---- expectedVersion 乐观锁 ----

    @Test
    void expectedVersionConflictsReturn409() {
        long caseId = approvedCaseViaInquiry("TEMU6000014");
        List<Long> items = itemIds(caseId);

        // 创建时期望数量错误 → 409
        assertThatThrownBy(() -> createChange(caseId, "co-v-bad", 5, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 1000L))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        ChangeOrderView created = createChange(caseId, "co-v", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 110000L)));

        // 复核旧版本 → 409
        assertThatThrownBy(() -> approve(created.id(), "carol", 9))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // 撤回旧版本 → 409
        assertThatThrownBy(() -> changeOrderService.withdraw(created.id(),
                new WithdrawChangeOrderRequest("wd-v-bad", 9)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        // 正确版本依次推进：1 → 2 → 3（生效）
        approve(created.id(), "carol", 1);
        ChangeOrderView done = approve(created.id(), "dave", 2);
        assertThat(done.version()).isEqualTo(3);
        assertThat(done.status()).isEqualTo(ChangeOrderStatus.EFFECTIVE);
    }

    // ---- 幂等 ----

    @Test
    void idempotentReplayAndConflictAndFailureNotOccupyingKey() {
        long caseId = approvedCaseViaInquiry("TEMU6000015");
        List<Long> items = itemIds(caseId);

        CreateChangeOrderRequest req = new CreateChangeOrderRequest("co-idem", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 110000L)));
        ChangeOrderView first = changeOrderService.create(caseId, req);
        ChangeOrderView replay = changeOrderService.create(caseId, req);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from change_order where case_id = ?",
                Integer.class, caseId)).isEqualTo(1);

        // 同键异参 → 409，不新增
        CreateChangeOrderRequest other = new CreateChangeOrderRequest("co-idem", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 120000L)));
        assertThatThrownBy(() -> changeOrderService.create(caseId, other))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("select count(*) from change_order where case_id = ?",
                Integer.class, caseId)).isEqualTo(1);

        // 失败不占键：同键先失败后成功
        String key = "co-fail-key";
        assertThatThrownBy(() -> createChange(caseId, key, 0, List.of(
                new ChangeLineInput(items.get(0), "NOT_A_METHOD", 1000L))))
                .isInstanceOf(ApiException.class);
        // 先撤回首张，再以同键成功申请
        changeOrderService.withdraw(first.id(), new WithdrawChangeOrderRequest("wd-idem", 1));
        ChangeOrderView ok = createChange(caseId, key, 1, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 115000L)));
        assertThat(ok.status()).isEqualTo(ChangeOrderStatus.PENDING);

        // 复核幂等：同键同参重放
        ChangeReviewRequest rv = new ChangeReviewRequest("crv-idem", 1, "carol", ChangeDecision.APPROVE);
        ChangeOrderView r1 = changeOrderService.review(ok.id(), rv);
        ChangeOrderView r2 = changeOrderService.review(ok.id(), rv);
        assertThat(r2).isEqualTo(r1);
        assertThat(jdbc.queryForObject("select count(*) from change_order_review where change_order_id = ?",
                Integer.class, ok.id())).isEqualTo(1);
    }

    // ---- 中标证据保留 ----

    @Test
    void originalInquiryBidAndApprovalEvidencePreservedAfterSwitch() {
        long caseId = approvedCaseViaInquiry("TEMU6000016");
        List<Long> items = itemIds(caseId);
        long inquiryId = jdbc.queryForObject("select id from inquiry where case_id = ?", Long.class, caseId);

        ChangeOrderView created = createChange(caseId, "co-ev", 0, List.of(
                new ChangeLineInput(items.get(0), "PATCH", 120000L)));
        approve(created.id(), "carol", 1);
        approve(created.id(), "dave", 2);

        // 原询价与报价不覆盖
        assertThat(inquiryService.getInquiry(inquiryId).status().name()).isEqualTo("CONVERTED");
        var bid = inquiryService.getBid(inquiryId, "W6000016");
        assertThat(bid.history()).hasSize(1);
        assertThat(bid.history().get(0).totalCents()).isEqualTo(150000);
        // 原开标冻结清单不变
        var opening = inquiryService.getOpening(inquiryId);
        assertThat(opening.winnerTotalCents()).isEqualTo(150000);
        assertThat(opening.winnerSupplierNo()).isEqualTo("W6000016");
        // 验收历史与旧批复全量保留
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.approvals()).hasSize(2);
        assertThat(detail.estimates()).hasSize(2);
        // 查询展示每项前后金额与工法及总额
        ChangeOrderView view = changeOrderService.get(created.id());
        assertThat(view.originalTotalCents()).isEqualTo(150000);
        assertThat(view.newTotalCents()).isEqualTo(170000);
        assertThat(view.lines()).allSatisfy(l -> {
            assertThat(l.beforeMethod()).isNotBlank();
            assertThat(l.afterMethod()).isNotBlank();
            assertThat(l.beforeAmountCents()).isPositive();
            assertThat(l.afterAmountCents()).isPositive();
        });
        assertThat(view.reviews()).hasSize(2);
    }
}
