package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.BidItemInput;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.AwardConfirmRequest;
import com.example.starter.damage.dto.Requests.BidSubmitRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.InquiryItemSpec;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRenewRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Views.AwardView;
import com.example.starter.damage.dto.Views.BidView;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.OpeningLineView;
import com.example.starter.damage.dto.Views.OpeningView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.SealedInquiryService;
import com.example.starter.damage.support.MutableUtcClock;
import com.example.starter.damage.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密封询价与比价业务流测试：真实 H2（MODE=MySQL）下验证固定项询价、
 * 截止时刻边界、整版报价拒绝、开标按开标时刻资格比价、同价供应商编号升序、
 * 双人不同复核转批复、批复后撤销仅风险事件、幂等指纹与版本递增。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SealedInquiryFlowTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    @Autowired
    private SealedInquiryService inquiryService;
    @Autowired
    private DamageCaseService caseService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableUtcClock clock;

    private long item1Id;
    private long item2Id;

    @BeforeEach
    void cleanTables() {
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
    }

    // ==================== 构造辅助 ====================

    private long repairCase(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + containerNo, containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + containerNo, List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("100.00")),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal("200.00")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + containerNo));
        CaseDetailView detail = caseService.detail(caseId);
        item1Id = detail.items().get(0).id();
        item2Id = detail.items().get(1).id();
        return caseId;
    }

    private long totalLossCase(String containerNo) {
        long caseId = caseService.register(new RegisterRequest(
                "reg-" + containerNo, containerNo, new BigDecimal("10000.00"))).id();
        caseService.survey(caseId, new SurveyRequest("svy-" + containerNo, List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("3000.01")))));
        caseService.estimate(caseId, new EstimateRequest("est-" + containerNo));
        return caseId;
    }

    private void createSupplier(String code, LocalDateTime validTo) {
        inquiryService.createSupplier(new SupplierCreateRequest(
                "sup-" + code, code, "供应商" + code,
                List.of("WELD", "PATCH"), T0.minusDays(30), validTo));
    }

    private InquiryView publish(long caseId, String reqId, int expectedVersion, LocalDateTime deadline) {
        return inquiryService.publishInquiry(caseId, new InquiryPublishRequest(
                reqId, expectedVersion, deadline, List.of(
                new InquiryItemSpec(item1Id, List.of("WELD")),
                new InquiryItemSpec(item2Id, List.of("PATCH")))));
    }

    private InquiryView publishDefault(long caseId) {
        return publish(caseId, "inq-1", 0, DEADLINE);
    }

    private BidView bid(InquiryView inquiry, String supplierCode, String reqId, int expectedVersion,
                        long amount1, long amount2) {
        List<Long> inquiryItemIds = inquiry.items().stream().map(InquiryItemView::id).toList();
        return inquiryService.submitBid(inquiry.id(), new BidSubmitRequest(
                reqId, supplierCode, expectedVersion, List.of(
                new BidItemInput(inquiryItemIds.get(0), "WELD", amount1),
                new BidItemInput(inquiryItemIds.get(1), "PATCH", amount2))));
    }

    private OpeningView open(long inquiryId, String reqId) {
        clock.setTime(DEADLINE.plusHours(1));
        return inquiryService.openBids(inquiryId, new BidOpenRequest(reqId));
    }

    // ==================== 主流程 ====================

    @Test
    void lowestTotalWins_doubleReview_convertsToOriginalApprovalAndFreezes() {
        long caseId = repairCase("TEMU3100001");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        createSupplier("SUP-B", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);
        assertThat(inquiry.version()).isEqualTo(1);
        assertThat(inquiry.items()).hasSize(2);
        assertThat(inquiry.items()).extracting(InquiryItemView::seqNo).containsExactly(1, 2);

        assertThat(bid(inquiry, "SUP-A", "bid-a", 0, 1000, 2000).revision()).isEqualTo(1);
        assertThat(bid(inquiry, "SUP-B", "bid-b", 0, 1500, 1400).revision()).isEqualTo(1);

        OpeningView opening = open(inquiry.id(), "open-1");
        assertThat(opening.winnerSupplierCode()).isEqualTo("SUP-B");
        assertThat(opening.winnerTotalAmountCents()).isEqualTo(2900);
        assertThat(opening.lines()).hasSize(2);
        assertThat(opening.lines()).filteredOn(OpeningLineView::qualified)
                .extracting(OpeningLineView::rankNo).containsExactly(1, 2);
        assertThat(opening.lines()).filteredOn(l -> l.supplierCode().equals("SUP-B"))
                .singleElement().satisfies(l -> assertThat(l.rankNo()).isEqualTo(1));

        // 同一复核人 422
        assertThatThrownBy(() -> inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-x", 1, "alice", "alice", LocalDate.of(2026, 10, 2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 修订号不符 409
        assertThatThrownBy(() -> inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-y", 9, "alice", "bob", LocalDate.of(2026, 10, 2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        AwardView award = inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-1", 1, "alice", "bob", LocalDate.of(2026, 10, 2)));
        assertThat(award.supplierCode()).isEqualTo("SUP-B");
        assertThat(award.qualificationVersion()).isEqualTo(1);
        assertThat(award.qualValidTo()).isEqualTo(DEADLINE.plusDays(10));

        // 原案状态与批复已生成，询价冻结
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status().name()).isEqualTo("APPROVED");
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).id()).isEqualTo(award.approvalId());
        assertThat(detail.approvals().get(0).approvalDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(inquiryService.inquiryDetail(inquiry.id()).status()).isEqualTo("FROZEN");

        // 中标凭证查询与冻结快照
        assertThat(inquiryService.awardDetail(caseId)).isEqualTo(award);

        // 转为原案批复后沿用既有放行链
        caseService.completeRepair(caseId,
                new CompletionRequest("cp-1", LocalDate.of(2026, 10, 3)));
        assertThat(caseService.reinspect(caseId, new ReinspectionRequest("ri-1", true)).status().name())
                .isEqualTo("REPAIR_PASSED");

        // 重复复核拒绝
        assertThatThrownBy(() -> inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-2", 1, "carol", "dave", LocalDate.of(2026, 10, 2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void sameTotal_tieBreaksBySupplierCodeAscending() {
        long caseId = repairCase("TEMU3100002");
        createSupplier("SUP-B", DEADLINE.plusDays(10));
        createSupplier("SUP-C", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);
        bid(inquiry, "SUP-B", "bid-b", 0, 1500, 1400);
        bid(inquiry, "SUP-C", "bid-c", 0, 1400, 1500);

        OpeningView opening = open(inquiry.id(), "open-tie");
        assertThat(opening.winnerSupplierCode()).isEqualTo("SUP-B");
        assertThat(opening.lines()).filteredOn(l -> l.supplierCode().equals("SUP-B"))
                .singleElement().satisfies(l -> assertThat(l.rankNo()).isEqualTo(1));
        assertThat(opening.lines()).filteredOn(l -> l.supplierCode().equals("SUP-C"))
                .singleElement().satisfies(l -> assertThat(l.rankNo()).isEqualTo(2));
    }

    // ==================== 截止时刻边界 ====================

    @Test
    void bidExactlyAtDeadlineRejected_oneSecondBeforeAccepted() {
        long caseId = repairCase("TEMU3100003");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);

        clock.setTime(DEADLINE.minusSeconds(1));
        assertThat(bid(inquiry, "SUP-A", "bid-before", 0, 1000, 2000).revision()).isEqualTo(1);

        clock.setTime(DEADLINE);
        assertThatThrownBy(() -> bid(inquiry, "SUP-A", "bid-exact", 1, 1000, 2001))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("截止");
        // 被拒修订没有落库，仍只有一个修订
        assertThat(inquiryService.listBids(inquiry.id())).hasSize(1);

        // 未到截止开标 422
        clock.setTime(DEADLINE.minusSeconds(1));
        assertThatThrownBy(() -> inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-early")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ==================== 整版报价失败分支（事务回滚，不留残数据） ====================

    @Test
    void invalidBidsWholeVersionRejectedWithNoRows() {
        long caseId = repairCase("TEMU3100004");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);

        List<Long> ids = inquiry.items().stream().map(InquiryItemView::id).toList();
        // 缺项
        assertThatThrownBy(() -> inquiryService.submitBid(inquiry.id(), new BidSubmitRequest(
                "bad-missing", "SUP-A", 0, List.of(new BidItemInput(ids.get(0), "WELD", 1000)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 金额 0 分（非正整数）
        assertThatThrownBy(() -> bid(inquiry, "SUP-A", "bad-zero", 0, 0, 2000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 工法不在允许集合
        assertThatThrownBy(() -> inquiryService.submitBid(inquiry.id(), new BidSubmitRequest(
                "bad-method", "SUP-A", 0, List.of(
                new BidItemInput(ids.get(0), "PATCH", 1000),
                new BidItemInput(ids.get(1), "PATCH", 2000)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 全部失败尝试后不存在任何报价与明细（整版事务回滚），且失败 requestId 未被占用
        assertThat(jdbc.queryForObject("select count(*) from bid", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bid_item", Integer.class)).isZero();
        for (String failedKey : List.of("bad-missing", "bad-zero", "bad-method")) {
            assertThat(jdbc.queryForObject(
                    "select count(*) from idempotency_record where request_id = ?", Integer.class, failedKey))
                    .isZero();
        }

        // 同一 requestId 可用于后续合法报价（失败不占键）
        assertThat(bid(inquiry, "SUP-A", "bad-zero", 0, 1000, 2000).revision()).isEqualTo(1);
    }

    @Test
    void supplierWithoutMethodQualificationRejected() {
        long caseId = repairCase("TEMU3100005");
        inquiryService.createSupplier(new SupplierCreateRequest(
                "sup-only", "SUP-ONLY", "仅焊工法", List.of("WELD"),
                T0.minusDays(30), DEADLINE.plusDays(10)));
        InquiryView inquiry = publishDefault(caseId);

        // 供应商不具备第二项 PATCH 工法：整版拒绝
        assertThatThrownBy(() -> bid(inquiry, "SUP-ONLY", "bad-skill", 0, 1000, 2000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("工法");
    }

    // ==================== 修订历史与版本守卫 ====================

    @Test
    void bidRevisionKeepsHistory_andExpectedVersionGuards() {
        long caseId = repairCase("TEMU3100006");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);

        BidView v1 = bid(inquiry, "SUP-A", "bid-v1", 0, 1000, 2000);
        BidView v2 = bid(inquiry, "SUP-A", "bid-v2", 1, 900, 1900);
        assertThat(v1.revision()).isEqualTo(1);
        assertThat(v2.revision()).isEqualTo(2);
        assertThat(v2.totalAmountCents()).isEqualTo(2800);

        // 过期修订号 409
        assertThatThrownBy(() -> bid(inquiry, "SUP-A", "bid-v3-bad", 1, 800, 1800))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        List<BidView> bids = inquiryService.listBids(inquiry.id());
        assertThat(bids).hasSize(2);
        assertThat(bids).extracting(BidView::status).containsExactly("SUPERSEDED", "CURRENT");

        // 同键同参重放首次结果，不新增修订
        assertThat(bid(inquiry, "SUP-A", "bid-v2", 1, 900, 1900)).isEqualTo(v2);
        assertThat(inquiryService.listBids(inquiry.id())).hasSize(2);

        // 同键异参 409
        assertThatThrownBy(() -> bid(inquiry, "SUP-A", "bid-v2", 1, 901, 1900))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        // 开标后冻结，不再接受修订
        open(inquiry.id(), "open-1");
        clock.setTime(DEADLINE.plusHours(2));
        assertThatThrownBy(() -> bid(inquiry, "SUP-A", "bid-after-open", 2, 1, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ==================== 开标时刻资格判断 ====================

    @Test
    void qualificationExpiredAtOpeningExcludes_andZeroValidBidsBlocksAward() {
        long caseId = repairCase("TEMU3100007");
        // 资格在截止时刻前失效：投标时有效，开标时已失效
        createSupplier("SUP-D", DEADLINE.minusHours(2));
        InquiryView inquiry = publishDefault(caseId);
        bid(inquiry, "SUP-D", "bid-d", 0, 1000, 2000);

        OpeningView opening = open(inquiry.id(), "open-exp");
        assertThat(opening.winnerSupplierCode()).isNull();
        assertThat(opening.winnerBidId()).isNull();
        assertThat(opening.lines()).singleElement().satisfies(l -> {
            assertThat(l.qualified()).isFalse();
            assertThat(l.excludeReason()).isEqualTo("SUPPLIER_QUALIFICATION_EXPIRED");
            assertThat(l.rankNo()).isNull();
        });

        // 零有效报价不得转批复
        assertThatThrownBy(() -> inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-x", 1, "alice", "bob", LocalDate.of(2026, 10, 2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("无有效报价");

        // 续期后重复开标仍返回首次冻结清单，不重算
        SupplierView renewed = inquiryService.renewQualification("SUP-D",
                new QualificationRenewRequest("renew-d", 1, T0, DEADLINE.plusDays(10)));
        assertThat(renewed.version()).isEqualTo(2);
        OpeningView replay = inquiryService.openBids(inquiry.id(), new BidOpenRequest("open-exp-2"));
        assertThat(replay.id()).isEqualTo(opening.id());
        assertThat(replay.lines()).singleElement()
                .satisfies(l -> assertThat(l.qualified()).isFalse());
    }

    @Test
    void qualificationRevokedBeforeOpeningExcludes_otherSupplierWins() {
        long caseId = repairCase("TEMU3100008");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        createSupplier("SUP-E", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);
        bid(inquiry, "SUP-A", "bid-a", 0, 1000, 2000);
        bid(inquiry, "SUP-E", "bid-e", 0, 5000, 5000);

        // 投标后、开标前撤销 E 的资格（即使其报价更高，也必须按撤销口径排除留痕）
        clock.setTime(DEADLINE.minusHours(1));
        SupplierView revoked = inquiryService.revokeQualification("SUP-E",
                new QualificationRevokeRequest("revoke-e", 1, "合成：现场核查不合格"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.version()).isEqualTo(2);
        // 已撤销供应商不可再投标
        assertThatThrownBy(() -> bid(inquiry, "SUP-E", "bid-e-2", 1, 1, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        OpeningView opening = open(inquiry.id(), "open-rev");
        assertThat(opening.winnerSupplierCode()).isEqualTo("SUP-A");
        assertThat(opening.lines()).hasSize(2);
        assertThat(opening.lines()).filteredOn(l -> l.supplierCode().equals("SUP-E"))
                .singleElement().satisfies(l -> {
                    assertThat(l.qualified()).isFalse();
                    assertThat(l.excludeReason()).isEqualTo("SUPPLIER_QUALIFICATION_REVOKED");
                });
    }

    // ==================== 批复后撤销只形成风险事件 ====================

    @Test
    void revokeAfterAwardOnlyRecordsRiskEvent_historyEvidenceUnchanged() {
        long caseId = repairCase("TEMU3100009");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);
        bid(inquiry, "SUP-A", "bid-a", 0, 1000, 2000);
        open(inquiry.id(), "open-1");
        AwardView award = inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-1", 1, "alice", "bob", LocalDate.of(2026, 10, 2)));

        // 批复后撤销中标供应商资格
        SupplierView revoked = inquiryService.revokeQualification("SUP-A",
                new QualificationRevokeRequest("revoke-a", 1, "合成：事后投诉成立"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.version()).isEqualTo(2);

        // 中标凭证、资格快照、询价/批复均不变
        assertThat(inquiryService.awardDetail(caseId)).isEqualTo(award);
        assertThat(inquiryService.inquiryDetail(inquiry.id()).status()).isEqualTo("FROZEN");
        CaseDetailView detail = caseService.detail(caseId);
        assertThat(detail.caseInfo().status().name()).isEqualTo("APPROVED");
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).id()).isEqualTo(award.approvalId());
        // 仅新增风险事件
        assertThat(detail.events()).extracting(e -> e.eventType()).contains("QUALIFICATION_RISK");
        assertThat(detail.events()).filteredOn(e -> e.eventType().equals("QUALIFICATION_RISK"))
                .singleElement().satisfies(e -> assertThat(e.payload()).contains("SUP-A"));

        // 重复撤销 422，版本守卫不匹配 409
        assertThatThrownBy(() -> inquiryService.revokeQualification("SUP-A",
                new QualificationRevokeRequest("revoke-a-2", 2, "再次撤销")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> inquiryService.revokeQualification("SUP-A",
                new QualificationRevokeRequest("revoke-a-3", 1, "旧版本撤销")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ==================== 询价发布规则与旧入口兼容 ====================

    @Test
    void publishRules_totalLossRejected_itemSetMustMatch_versionSupercede() {
        // 全损案件不得发布询价
        long lossCase = totalLossCase("TEMU3100010");
        long lossItem = caseService.detail(lossCase).items().get(0).id();
        assertThatThrownBy(() -> inquiryService.publishInquiry(lossCase, new InquiryPublishRequest(
                "inq-loss", 0, DEADLINE, List.of(new InquiryItemSpec(lossItem, List.of("WELD"))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        long caseId = repairCase("TEMU3100011");
        // 残损项集合不匹配
        assertThatThrownBy(() -> inquiryService.publishInquiry(caseId, new InquiryPublishRequest(
                "inq-bad", 0, DEADLINE, List.of(new InquiryItemSpec(item1Id, List.of("WELD"))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        InquiryView v1 = publishDefault(caseId);
        // 首版后 expectedVersion 不匹配 409
        assertThatThrownBy(() -> publish(caseId, "dup-0", 0, DEADLINE.plusDays(1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // 发布新版本：旧版 SUPERSEDED，新版从 2 开始且仍为 OPEN
        InquiryView v2 = publish(caseId, "inq-2", 1, DEADLINE.plusDays(1));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(inquiryService.inquiryDetail(v1.id()).status()).isEqualTo("SUPERSEDED");
        assertThat(inquiryService.inquiryDetail(v2.id()).status()).isEqualTo("OPEN");

        // 已发布询价的案件旧批复入口 422、重新估价 422
        assertThatThrownBy(() -> caseService.approve(caseId,
                new com.example.starter.damage.dto.Requests.ApprovalRequest(
                        "legacy-ap", LocalDate.of(2026, 10, 2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> caseService.estimate(caseId, new EstimateRequest("re-est")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 开标后不得再发布新版本
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        bid(v2, "SUP-A", "bid-a", 0, 1000, 2000);
        clock.setTime(DEADLINE.plusDays(1).plusHours(1));
        inquiryService.openBids(v2.id(), new BidOpenRequest("open-v2"));
        assertThatThrownBy(() -> publish(caseId, "inq-3", 2, DEADLINE.plusDays(2)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void legacyApprovalWithoutInquiryStillCompatible() {
        // 无询价旧案：旧批复入口保持可用
        long caseId = repairCase("TEMU3100012");
        caseService.approve(caseId, new com.example.starter.damage.dto.Requests.ApprovalRequest(
                "legacy-ap", LocalDate.of(2026, 10, 1)));
        assertThat(caseService.detail(caseId).caseInfo().status().name()).isEqualTo("APPROVED");
    }

    // ==================== 404 与幂等指纹 ====================

    @Test
    void qualificationRenewedAfterOpening_confirmStillFreezesOpeningVersion() {
        long caseId = repairCase("TEMU3100015");
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        InquiryView inquiry = publishDefault(caseId);
        bid(inquiry, "SUP-A", "bid-a", 0, 1000, 2000);
        OpeningView opening = open(inquiry.id(), "open-1");
        assertThat(opening.lines()).singleElement()
                .satisfies(l -> assertThat(l.qualificationVersion()).isEqualTo(1));

        // 开标后续期产生资格版本 2
        inquiryService.renewQualification("SUP-A",
                new QualificationRenewRequest("renew-a", 1, DEADLINE, DEADLINE.plusDays(30)));

        // 双人复核仍冻结开标时有效的版本 1，不因后来资格变化改取新版本
        AwardView award = inquiryService.confirmAward(inquiry.id(),
                new AwardConfirmRequest("award-1", 1, "alice", "bob", LocalDate.of(2026, 10, 2)));
        assertThat(award.qualificationVersion()).isEqualTo(1);
        assertThat(award.qualValidTo()).isEqualTo(DEADLINE.plusDays(10));
        assertThat(inquiryService.awardDetail(caseId)).isEqualTo(award);
    }

    @Test
    void missingObjectsReturn404() {
        assertThatThrownBy(() -> inquiryService.supplierDetail("NOBODY"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> inquiryService.inquiryDetail(999999))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        long caseId = repairCase("TEMU3100013");
        InquiryView inquiry = publishDefault(caseId);
        long inquiryId = inquiry.id();
        assertThatThrownBy(() -> inquiryService.openingDetail(inquiryId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> inquiryService.awardDetail(caseId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        // 未知供应商投标
        assertThatThrownBy(() -> bid(inquiry, "GHOST", "bid-ghost", 0, 1, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void sameKeyDifferentParams409_evenWhenTextContainsSeparator() {
        long caseId = repairCase("TEMU3100014");
        // 撤销原因中包含指纹分隔符 "|"，结构化 JSON 信封仍须区分异参
        createSupplier("SUP-A", DEADLINE.plusDays(10));
        inquiryService.revokeQualification("SUP-A",
                new QualificationRevokeRequest("rev-pipe", 1, "原因|含分隔符"));
        assertThatThrownBy(() -> inquiryService.revokeQualification("SUP-A",
                new QualificationRevokeRequest("rev-pipe", 1, "原因含分隔符")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
