package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.SealedRequests.BidLineInput;
import com.example.starter.damage.dto.SealedRequests.OpenBidsRequest;
import com.example.starter.damage.dto.SealedRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRenewRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.SealedRequests.ReviewRequest;
import com.example.starter.damage.dto.SealedRequests.SubmitBidRequest;
import com.example.starter.damage.dto.SealedViews.BidView;
import com.example.starter.damage.dto.SealedViews.InquiryView;
import com.example.starter.damage.dto.SealedViews.OpeningView;
import com.example.starter.damage.dto.SealedViews.RevokeResultView;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.service.DamageCaseService;
import com.example.starter.damage.service.SealedBidService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.SealedClockConfig;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密封比价主流程、失败分支与幂等边界业务测试（真实 H2 MySQL 兼容模式 + 可控 UTC 时钟）。
 */
@SpringBootTest
@Import(SealedClockConfig.class)
class SealedBidFlowTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 27, 0, 0, 0);
    private static final LocalDateTime DEADLINE = T0.plusHours(1);

    @Autowired
    private SealedBidService sealed;
    @Autowired
    private DamageCaseService cases;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private int containerSeq = 100;

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
        reseedQualifications();
        clock.setInstant(SealedClockConfig.START);
    }

    /** 重置合成供应商资格为种子版本 v1（HM/WD/SP、WD/RP、HM/PT/SP，覆盖 2026 全年） */
    private void reseedQualifications() {
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
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'HM' code union all select 'PT' union all select 'SP') m"
                + " where s.supplier_no = 'SUP-300'");
    }

    private record PreparedCase(long caseId, long item1, long item2) {
    }

    private PreparedCase repairCase(String value, String c1, String c2) {
        String containerNo = "TEMU" + (containerSeq++);
        long caseId = cases.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal(value))).id();
        cases.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal(c1)),
                new DamageItemInput("BR", "底梁", "弯曲", Responsibility.TERMINAL, new BigDecimal(c2)))));
        cases.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        CaseDetailView detail = cases.detail(caseId);
        return new PreparedCase(caseId, detail.items().get(0).id(), detail.items().get(1).id());
    }

    private PreparedCase totalLossCase() {
        String containerNo = "TEMU" + (containerSeq++);
        long caseId = cases.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), containerNo, new BigDecimal("10000.00"))).id();
        cases.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("3000.01")))));
        cases.estimate(caseId, new EstimateRequest("est-" + UUID.randomUUID()));
        return new PreparedCase(caseId, cases.detail(caseId).items().get(0).id(), 0);
    }

    private PublishInquiryRequest wideInquiry(String requestId, int expectedVersion,
                                              long item1, long item2, LocalDateTime deadline) {
        return new PublishInquiryRequest(requestId, expectedVersion, deadline, List.of(
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(
                        item1, List.of("HM", "WD")),
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(
                        item2, List.of("HM", "WD", "RP", "PT", "SP"))));
    }

    private SubmitBidRequest bid(String requestId, String supplierNo, int expectedVersion,
                                 Object... seqMethodPrice) {
        List<BidLineInput> lines = new java.util.ArrayList<>();
        for (int i = 0; i < seqMethodPrice.length; i += 3) {
            lines.add(new BidLineInput((Integer) seqMethodPrice[i], (String) seqMethodPrice[i + 1],
                    ((Number) seqMethodPrice[i + 2]).longValue()));
        }
        return new SubmitBidRequest(requestId, supplierNo, expectedVersion, lines);
    }

    private void moveAfterDeadline() {
        clock.setInstant(DEADLINE.plusSeconds(1).toInstant(ZoneOffset.UTC));
    }

    // ==================== 主流程 ====================

    @Test
    void sealedBiddingFlow_lowestWins_twoReviewersConvertToApproval() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        InquiryView inquiry = sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-1", 1, pc.item1(), pc.item2(), DEADLINE));
        assertThat(inquiry.version()).isEqualTo(1);
        assertThat(inquiry.status()).isEqualTo("OPEN");
        assertThat(inquiry.items()).hasSize(2);
        assertThat(inquiry.items().get(0).allowedMethods()).containsExactly("HM", "WD");

        BidView sup100 = sealed.submitBid(pc.caseId(), 1,
                bid("bid-100-1", "SUP-100", 1, 1, "HM", 100000, 2, "WD", 200000));
        assertThat(sup100.revision()).isEqualTo(1);
        assertThat(sup100.totalCents()).isEqualTo(300000);
        BidView sup200 = sealed.submitBid(pc.caseId(), 1,
                bid("bid-200-1", "SUP-200", 1, 1, "WD", 200000, 2, "RP", 50000));
        assertThat(sup200.totalCents()).isEqualTo(250000);

        moveAfterDeadline();
        OpeningView opening = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-1"));
        assertThat(opening.status()).isEqualTo("SELECTED");
        assertThat(opening.winnerSupplierNo()).isEqualTo("SUP-200");
        assertThat(opening.totalCents()).isEqualTo(250000);
        assertThat(opening.converted()).isFalse();
        assertThat(opening.rankedBids()).hasSize(2);
        assertThat(opening.rankedBids().get(0).result()).isEqualTo("WINNER");

        OpeningView firstReview = sealed.review(pc.caseId(), 1, new ReviewRequest("rv-1", "alice"));
        assertThat(firstReview.converted()).isFalse();
        assertThat(firstReview.reviewers()).containsExactly("alice");

        // 双人不同复核完成 → 转原案修理批复
        OpeningView secondReview = sealed.review(pc.caseId(), 1, new ReviewRequest("rv-2", "bob"));
        assertThat(secondReview.converted()).isTrue();
        assertThat(secondReview.reviewers()).containsExactly("alice", "bob");
        assertThat(secondReview.approvalId()).isNotNull();

        CaseDetailView detail = cases.detail(pc.caseId());
        assertThat(detail.caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        assertThat(detail.approvals()).hasSize(1);
        assertThat(detail.approvals().get(0).id()).isEqualTo(secondReview.approvalId());
        assertThat(sealed.inquiry(pc.caseId(), 1).status()).isEqualTo("CONVERTED");
    }

    @Test
    void tiePriceWinnerBySupplierNoAscending() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), new PublishInquiryRequest("pub-tie", 1, DEADLINE, List.of(
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(pc.item1(), List.of("HM")),
                new com.example.starter.damage.dto.SealedRequests.InquiryItemInput(pc.item2(), List.of("SP")))));
        // SUP-100 与 SUP-300 均具备 HM+SP，报价同为 100000 分
        sealed.submitBid(pc.caseId(), 1, bid("bid-tie-100", "SUP-100", 1, 1, "HM", 50000, 2, "SP", 50000));
        sealed.submitBid(pc.caseId(), 1, bid("bid-tie-300", "SUP-300", 1, 1, "HM", 50000, 2, "SP", 50000));
        moveAfterDeadline();

        OpeningView opening = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-tie"));
        assertThat(opening.winnerSupplierNo()).isEqualTo("SUP-100");
    }

    // ==================== 投标失败分支 ====================

    @Test
    void bidExactlyAtDeadlineRejected_beforeDeadlineAccepted() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-d", 1, pc.item1(), pc.item2(), DEADLINE));

        clock.setInstant(DEADLINE.toInstant(ZoneOffset.UTC)); // 恰在截止时刻
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-exact", "SUP-100", 1, 1, "HM", 1, 2, "WD", 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        clock.setInstant(DEADLINE.minusSeconds(1).toInstant(ZoneOffset.UTC)); // 截止前一秒
        assertThat(sealed.submitBid(pc.caseId(), 1,
                bid("bid-before", "SUP-100", 1, 1, "HM", 1, 2, "WD", 1)).revision()).isEqualTo(1);
    }

    @Test
    void bidNotCoveringAllItemsWholeVersionRejected() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-cov", 1, pc.item1(), pc.item2(), DEADLINE));
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-missing", "SUP-100", 1, 1, "HM", 100)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 整版拒绝：不留任何报价
        Integer count = jdbc.queryForObject("select count(*) from bid", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void bidNonPositiveAmountRejected400() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-amt", 1, pc.item1(), pc.item2(), DEADLINE));
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-zero", "SUP-100", 1, 1, "HM", 0, 2, "WD", 100)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void bidMethodNotAllowedWholeVersionRejected() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-ma", 1, pc.item1(), pc.item2(), DEADLINE));
        // 第 1 项询价仅允许 HM/WD，报 PT 越界
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-ma", "SUP-300", 1, 1, "PT", 100, 2, "PT", 100)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void bidMethodNotCoveredByQualificationWholeVersionRejected() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-mq", 1, pc.item1(), pc.item2(), DEADLINE));
        // SUP-200 资格仅 WD/RP，第 1 项报 HM 不被资格覆盖
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-mq", "SUP-200", 1, 1, "HM", 100, 2, "RP", 100)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void bidRevisionKeepsHistoryAndExpectedVersionGuards() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-rev", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-r1", "SUP-100", 1, 1, "HM", 100, 2, "WD", 100));

        // expectedVersion 不匹配：下一修订号应为 2
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-rbad", "SUP-100", 1, 1, "HM", 90, 2, "WD", 90)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        BidView revised = sealed.submitBid(pc.caseId(), 1,
                bid("bid-r2", "SUP-100", 2, 1, "HM", 90, 2, "WD", 90));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.totalCents()).isEqualTo(180);

        List<String> statuses = jdbc.queryForList(
                "select status from bid join supplier s on s.id = bid.supplier_id"
                        + " where s.supplier_no = 'SUP-100' order by revision", String.class);
        assertThat(statuses).containsExactly("SUPERSEDED", "CURRENT");
    }

    // ==================== 询价失败分支 ====================

    @Test
    void publishExpectedVersionMismatchReturns409() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        assertThatThrownBy(() -> sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-badver", 2, pc.item1(), pc.item2(), DEADLINE)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void publishOnTotalLossCaseRejected422() {
        PreparedCase pc = totalLossCase();
        assertThatThrownBy(() -> sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-tl", 1, pc.item1(), pc.item2(), DEADLINE)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void publishWithPastDeadlineRejected422() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        assertThatThrownBy(() -> sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-past", 1, pc.item1(), pc.item2(), T0.minusSeconds(1))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void secondOpenInquiryBlockedUntilOpened_thenNewVersionAllowed() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-v1", 1, pc.item1(), pc.item2(), DEADLINE));
        assertThatThrownBy(() -> sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-v2-early", 2, pc.item1(), pc.item2(), DEADLINE.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        moveAfterDeadline();
        // v1 零有效报价开标
        OpeningView empty = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-empty"));
        assertThat(empty.status()).isEqualTo("NO_VALID_BID");
        assertThat(empty.rankedBids()).isEmpty();
        assertThatThrownBy(() -> sealed.review(pc.caseId(), 1, new ReviewRequest("rv-x", "alice")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 零有效报价不开批复，可发布新版本
        InquiryView v2 = sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-v2", 2, pc.item1(), pc.item2(), DEADLINE.plusHours(2)));
        assertThat(v2.version()).isEqualTo(2);
    }

    // ==================== 开标资格重算、重复开标 ====================

    @Test
    void qualificationRevokedBeforeOpening_bidExcludedAndReasonFrozen() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-rev", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-a", "SUP-100", 1, 1, "HM", 400000, 2, "WD", 400000));
        sealed.submitBid(pc.caseId(), 1, bid("bid-b", "SUP-200", 1, 1, "WD", 200000, 2, "RP", 50000));

        // 开标前撤销 SUP-200（投标时资格有效）：开标竞争须与撤销一致
        RevokeResultView revoke = sealed.revokeQualification("SUP-200",
                new QualificationRevokeRequest("revoke-200"));
        assertThat(revoke.result()).isEqualTo("REVOKED");

        moveAfterDeadline();
        OpeningView opening = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-rev"));
        assertThat(opening.winnerSupplierNo()).isEqualTo("SUP-100");
        assertThat(opening.rankedBids())
                .filteredOn(b -> b.supplierNo().equals("SUP-200"))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.result()).isEqualTo("EXCLUDED");
                    assertThat(b.exclusionReason()).isEqualTo("QUALIFICATION_REVOKED");
                });
    }

    @Test
    void qualificationExpiredAtOpening_bidExcludedUsingOpeningTimeNotBidTime() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-exp", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-exp", "SUP-100", 1, 1, "HM", 100, 2, "WD", 100));

        // 开标时刻推进到 2027 年：种子资格 2026-12-31 到期，不能采用提交时旧资格
        clock.setInstant(LocalDateTime.of(2027, 1, 2, 0, 0).toInstant(ZoneOffset.UTC));
        OpeningView opening = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-exp"));
        assertThat(opening.status()).isEqualTo("NO_VALID_BID");
        assertThat(opening.rankedBids()).singleElement()
                .satisfies(b -> {
                    assertThat(b.result()).isEqualTo("EXCLUDED");
                    assertThat(b.exclusionReason()).isEqualTo("QUALIFICATION_EXPIRED");
                });
    }

    @Test
    void repeatedOpeningReturnsFirstResultAndDoesNotRecompute() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-rep", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-rep", "SUP-100", 1, 1, "HM", 100, 2, "WD", 100));
        moveAfterDeadline();
        OpeningView first = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-rep-1"));

        // 同 requestId 重放首次冻结结果
        OpeningView replay = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-rep-1"));
        assertThat(replay).isEqualTo(first);
        // 不同 requestId 重复开标也返回首次清单（不新增开标行）
        OpeningView again = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-rep-2"));
        assertThat(again.id()).isEqualTo(first.id());

        // 事后撤销资格：首次中标证据不重算、不改写
        sealed.review(pc.caseId(), 1, new ReviewRequest("rv-a", "alice"));
        sealed.review(pc.caseId(), 1, new ReviewRequest("rv-b", "bob"));
        sealed.revokeQualification("SUP-100", new QualificationRevokeRequest("revoke-after"));
        OpeningView frozen = sealed.opening(pc.caseId(), 1);
        assertThat(frozen.winnerSupplierNo()).isEqualTo("SUP-100");
        assertThat(frozen.converted()).isTrue();
    }

    @Test
    void sameReviewerTwiceRejected_andRevocationBeforeConversionBlocksApproval() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-blk", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-blk", "SUP-200", 1, 1, "WD", 100, 2, "RP", 100));
        moveAfterDeadline();
        sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-blk"));

        sealed.review(pc.caseId(), 1, new ReviewRequest("rv-blk-1", "alice"));
        assertThatThrownBy(() -> sealed.review(pc.caseId(), 1, new ReviewRequest("rv-blk-x", "alice")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 转换前撤销中标资格：竞争结果保留但不得转批复
        sealed.revokeQualification("SUP-200", new QualificationRevokeRequest("revoke-blk"));
        assertThatThrownBy(() -> sealed.review(pc.caseId(), 1, new ReviewRequest("rv-blk-2", "bob")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(cases.detail(pc.caseId()).caseInfo().status()).isEqualTo(CaseStatus.ESTIMATED);
        assertThat(sealed.opening(pc.caseId(), 1).converted()).isFalse();
    }

    // ==================== 批复后撤销：只形成风险事件 ====================

    @Test
    void revocationAfterConversionOnlyRecordsRiskEvent() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-risk", 1, pc.item1(), pc.item2(), DEADLINE));
        sealed.submitBid(pc.caseId(), 1, bid("bid-risk", "SUP-200", 1, 1, "WD", 100, 2, "RP", 100));
        moveAfterDeadline();
        sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-risk"));
        sealed.review(pc.caseId(), 1, new ReviewRequest("rv-risk-1", "alice"));
        sealed.review(pc.caseId(), 1, new ReviewRequest("rv-risk-2", "bob"));

        RevokeResultView result = sealed.revokeQualification("SUP-200",
                new QualificationRevokeRequest("revoke-risk"));
        assertThat(result.result()).isEqualTo("RISK_RECORDED");

        // 历史中标证据不改写，案件仍 APPROVED
        assertThat(sealed.opening(pc.caseId(), 1).winnerSupplierNo()).isEqualTo("SUP-200");
        assertThat(cases.detail(pc.caseId()).caseInfo().status()).isEqualTo(CaseStatus.APPROVED);
        CaseDetailView detail = cases.detail(pc.caseId());
        assertThat(detail.events())
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo("SUPPLIER_RISK");
                    assertThat(e.payload()).contains("SUP-200");
                });
        // 中标采用的资格版本行不被置 REVOKED
        String qualStatus = jdbc.queryForObject(
                "select q.status from bid b join supplier_qualification q on q.id = b.qualification_id"
                        + " join supplier s on s.id = b.supplier_id where s.supplier_no = 'SUP-200'",
                String.class);
        assertThat(qualStatus).isEqualTo("ACTIVE");
    }

    // ==================== 资格续期版本 ====================

    @Test
    void qualificationRenewAppendsVersion_andExpectedVersionGuards() {
        assertThatThrownBy(() -> sealed.renewQualification("SUP-100", new QualificationRenewRequest(
                "ren-bad", 3, T0, T0.plusDays(30), List.of("HM"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));

        var renewed = sealed.renewQualification("SUP-100", new QualificationRenewRequest(
                "ren-1", 2, T0, T0.plusDays(30), List.of("RP", "HM")));
        assertThat(renewed.version()).isEqualTo(2);
        assertThat(renewed.methods()).containsExactly("HM", "RP");

        Integer versions = jdbc.queryForObject(
                "select count(*) from supplier_qualification q join supplier s on s.id = q.supplier_id"
                        + " where s.supplier_no = 'SUP-100'", Integer.class);
        assertThat(versions).isEqualTo(2);
    }

    // ==================== 幂等边界 ====================

    @Test
    void idempotentReplaySameKeySameParams_andDifferentParams409() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        PublishInquiryRequest first = wideInquiry("pub-idem", 1, pc.item1(), pc.item2(), DEADLINE);
        InquiryView v1 = sealed.publishInquiry(pc.caseId(), first);
        InquiryView replay = sealed.publishInquiry(pc.caseId(), first);
        assertThat(replay).isEqualTo(v1);

        // 同键异参（截止时刻不同）→ 409
        assertThatThrownBy(() -> sealed.publishInquiry(pc.caseId(),
                wideInquiry("pub-idem", 1, pc.item1(), pc.item2(), DEADLINE.plusHours(5))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        Integer inquiryCount = jdbc.queryForObject("select count(*) from inquiry", Integer.class);
        assertThat(inquiryCount).isOne();
    }

    @Test
    void failedBusinessCallDoesNotOccupyKey() {
        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-free", 1, pc.item1(), pc.item2(), DEADLINE));

        // 未到截止开标 → 422，失败不占键
        assertThatThrownBy(() -> sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-free")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        Integer idemCount = jdbc.queryForObject(
                "select count(*) from idempotency_record where request_id = 'open-free'", Integer.class);
        assertThat(idemCount).isZero();

        moveAfterDeadline();
        // 同键随后可成功，并冻结首次结果
        OpeningView opening = sealed.openBids(pc.caseId(), 1, new OpenBidsRequest("open-free"));
        assertThat(opening.status()).isEqualTo("NO_VALID_BID");
    }

    @Test
    void unknownObjectsReturn404() {
        assertThatThrownBy(() -> sealed.inquiry(999999, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));

        PreparedCase pc = repairCase("10000.00", "1000.00", "500.00");
        assertThatThrownBy(() -> sealed.inquiry(pc.caseId(), 7))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));

        sealed.publishInquiry(pc.caseId(), wideInquiry("pub-404", 1, pc.item1(), pc.item2(), DEADLINE));
        assertThatThrownBy(() -> sealed.submitBid(pc.caseId(), 1,
                bid("bid-404", "SUP-999", 1, 1, "HM", 1, 2, "WD", 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
