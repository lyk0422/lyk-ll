package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReservationView;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关单核销主流程与失败分支测试（H2 MODE=MySQL 真实库）：
 * 登记 → 申报 → 绑定预占 → 公示生效 → 签发原子核销；及配额、资料不符、版本、
 * 有效期、撤销、修订、撤回等失败分支。
 */
class CustomsFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-07T02:00:00Z");
    private static final LocalDate VALID_UNTIL = LocalDate.of(2026, 10, 31);
    private static final LocalDate DEADLINE = LocalDate.of(2026, 9, 20);
    private static final Instant OCCURRED = Instant.parse("2026-09-07T02:00:00Z");

    @Autowired
    private CustomsService customsService;

    @BeforeEach
    void fixClock() {
        clock.setFixed(T0);
    }

    // ============================ 主流程 ============================

    @Test
    void importedBatchFullFlowWritesOffReservationAtomicallyAtIssue() {
        register("CD-1", 100);
        declareBatch("req-d1", "B-IMP-1");
        ServiceResult bound = bind("req-b1", "CD-1", "B-IMP-1", 30, 1);
        assertEquals(200, bound.httpStatus());
        assertDeclaration("CD-1", 100, 30, 0, 70, 2);

        toPublicityEffective("B-IMP-1");
        ServiceResult issued = reviewService.issue("B-IMP-1", new IssueRequest("req-i1"));
        assertEquals(200, issued.httpStatus());

        // 签发原子：已发证且余量已扣（预占转已核销），同事务可见。
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-IMP-1").status());
        assertDeclaration("CD-1", 100, 0, 30, 70, 2);
        assertReservation("B-IMP-1", "WRITTEN_OFF", 2);
        assertTrue(reviewService.listEvents("B-IMP-1").stream()
                .anyMatch(e -> e.get("eventType").equals("CUSTOMS_WRITTEN_OFF")));
    }

    @Test
    void domesticBatchIssuesWithoutCustomsGate() {
        declareBatch("req-d2", "B-DOM-1");
        toPublicityEffective("B-DOM-1");
        ServiceResult issued = reviewService.issue("B-DOM-1", new IssueRequest("req-i2"));
        assertEquals(200, issued.httpStatus(), "国内批次不新增通关门禁");
        assertEquals(0, countReservations());
    }

    @Test
    void multipleSubBatchesShareOneDeclarationAndExactFillIsLegal() {
        register("CD-2", 100);
        declareBatch("req-d3", "B-IMP-2");
        declareBatch("req-d4", "B-IMP-3");
        assertEquals(200, bind("req-b2", "CD-2", "B-IMP-2", 60, 1).httpStatus());
        // 恰占满合法：60 + 40 = 100。
        assertEquals(200, bind("req-b3", "CD-2", "B-IMP-3", 40, 2).httpStatus());
        assertDeclaration("CD-2", 100, 100, 0, 0, 3);
    }

    // ============================ 绑定失败分支 ============================

    @Test
    void bindRejectsQuotaExceededMaterialMismatchAndDuplicateBinding() {
        register("CD-3", 100);
        declareBatch("req-d5", "B-IMP-4");
        declareBatch("req-d6", "B-IMP-5");

        // 超量：累计 101 > 100，整笔拒绝。
        ServiceResult exceed = bind("req-b4", "CD-3", "B-IMP-4", 101, 1);
        assertEquals(422, exceed.httpStatus());
        assertEquals(0, countReservations());

        assertEquals(200, bind("req-b5", "CD-3", "B-IMP-4", 60, 1).httpStatus());
        ServiceResult overflow = bind("req-b6", "CD-3", "B-IMP-5", 41, 2);
        assertEquals(422, overflow.httpStatus(), "累计预占不得超过可核销总数");

        // 每个子批只能绑定一张单。
        register("CD-4", 100);
        ServiceResult rebound = bind("req-b7", "CD-4", "B-IMP-4", 10, 1);
        assertEquals(422, rebound.httpStatus());
    }

    @Test
    void bindRejectsEnterpriseOrProductMismatch() {
        // 通关单登记企业/品种与子批不一致：任一资料不符整笔拒绝。
        customsService.register(new CustomsRegisterRequest(
                "req-r9", "CD-5", "另一企业", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        declareBatch("req-d7", "B-IMP-6");
        ServiceResult mismatch = bind("req-b8", "CD-5", "B-IMP-6", 10, 1);
        assertEquals(422, mismatch.httpStatus());
        assertEquals(0, countReservations());

        customsService.register(new CustomsRegisterRequest(
                "req-r10", "CD-6", "合成生物制品厂", "另一品种", VALID_UNTIL, 100));
        ServiceResult productMismatch = bind("req-b9", "CD-6", "B-IMP-6", 10, 1);
        assertEquals(422, productMismatch.httpStatus());
    }

    @Test
    void bindRejectsUnknownDeclarationUnknownBatchAndVersionMismatch() {
        register("CD-7", 100);
        declareBatch("req-d8", "B-IMP-7");

        assertEquals(404, bind("req-b10", "CD-UNKNOWN", "B-IMP-7", 10, 1).httpStatus());
        assertEquals(404, bind("req-b11", "CD-7", "B-UNKNOWN", 10, 1).httpStatus());
        ServiceResult stale = bind("req-b12", "CD-7", "B-IMP-7", 10, 5);
        assertEquals(422, stale.httpStatus(), "期望版本不符整笔拒绝");
        assertEquals(0, countReservations());
    }

    @Test
    void duplicateDeclarationNoIsRejected() {
        register("CD-8", 100);
        ServiceResult duplicate = customsService.register(new CustomsRegisterRequest(
                "req-r12", "CD-8", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 50));
        assertEquals(422, duplicate.httpStatus());
    }

    // ============================ 有效期边界 ============================

    @Test
    void issueBeforeDeadlineSucceedsAndExactlyAtDeadlineIsRejected() {
        register("CD-9", 100, DEADLINE);
        declareBatch("req-d9", "B-IMP-8");
        bind("req-b13", "CD-9", "B-IMP-8", 10, 1);
        toPublicityEffective("B-IMP-8");

        // 恰截止日（UTC 2026-09-20）签发拒绝。
        clock.setFixed(DEADLINE.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        ServiceResult atDeadline = reviewService.issue("B-IMP-8", new IssueRequest("req-i3"));
        assertEquals(422, atDeadline.httpStatus());
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-IMP-8").status());
        assertReservation("B-IMP-8", "RESERVED", 1);

        // 截止前一日签发成功。
        clock.setFixed(T0);
        register("CD-10", 100, DEADLINE);
        declareBatch("req-d10", "B-IMP-9");
        bind("req-b14", "CD-10", "B-IMP-9", 10, 1);
        toPublicityEffective("B-IMP-9");
        clock.setFixed(Instant.parse("2026-09-19T23:59:59Z"));
        ServiceResult beforeDeadline = reviewService.issue("B-IMP-9", new IssueRequest("req-i4"));
        assertEquals(200, beforeDeadline.httpStatus());
        assertDeclaration("CD-10", 100, 0, 10, 90, 2);
    }

    // ============================ 签发前撤回 ============================

    @Test
    void withdrawBeforeIssueReleasesReservationExactlyOnce() {
        register("CD-11", 100);
        declareBatch("req-d11", "B-IMP-10");
        bind("req-b15", "CD-11", "B-IMP-10", 25, 1);
        assertDeclaration("CD-11", 100, 25, 0, 75, 2);

        ServiceResult withdrawn = customsService.withdraw(
                new CustomsWithdrawRequest("req-w1", "B-IMP-10", 1, OCCURRED));
        assertEquals(200, withdrawn.httpStatus());
        assertEquals(ReviewStatus.WITHDRAWN.name(), reviewService.getReview("B-IMP-10").status());
        assertReservation("B-IMP-10", "RELEASED", 2);
        assertDeclaration("CD-11", 100, 0, 0, 100, 2);

        // 只释放一次：再次撤回（携带最新版本）业务拒绝。
        ServiceResult again = customsService.withdraw(
                new CustomsWithdrawRequest("req-w2", "B-IMP-10", 2, OCCURRED));
        assertEquals(422, again.httpStatus());
        assertDeclaration("CD-11", 100, 0, 0, 100, 2);

        // 撤回后不能再签发。
        ServiceResult issue = reviewService.issue("B-IMP-10", new IssueRequest("req-i5"));
        assertEquals(409, issue.httpStatus());
    }

    @Test
    void withdrawRejectsUnknownBatchAndUnboundBatch() {
        declareBatch("req-d12", "B-DOM-2");
        assertEquals(404, customsService.withdraw(
                new CustomsWithdrawRequest("req-w3", "B-UNKNOWN", 1, OCCURRED)).httpStatus());
        assertEquals(404, customsService.withdraw(
                new CustomsWithdrawRequest("req-w4", "B-DOM-2", 1, OCCURRED)).httpStatus());
    }

    // ============================ 通关单撤销 ============================

    @Test
    void revokeReleasesUnissuedBlocksIssueAndKeepsIssuedEvidence() {
        register("CD-12", 100);
        declareBatch("req-d13", "B-IMP-11");
        declareBatch("req-d14", "B-IMP-12");
        bind("req-b16", "CD-12", "B-IMP-11", 30, 1);
        bind("req-b17", "CD-12", "B-IMP-12", 20, 2);

        // 先签发 B-IMP-11（核销 30 支）。
        toPublicityEffective("B-IMP-11");
        assertEquals(200, reviewService.issue("B-IMP-11", new IssueRequest("req-i6")).httpStatus());

        // 撤销：未签发的 B-IMP-12 预占释放，已签发证据保留并记风险事件。
        ServiceResult revoked = customsService.revoke(
                new CustomsRevokeRequest("req-v1", "CD-12", 3, OCCURRED));
        assertEquals(200, revoked.httpStatus());
        assertDeclarationRevoked("CD-12", 0, 30, 4);
        assertReservation("B-IMP-12", "RELEASED", 2);
        assertReservation("B-IMP-11", "WRITTEN_OFF", 2);

        // 撤销阻断签发：B-IMP-12 公示生效后签发仍被拒绝。
        toPublicityEffective("B-IMP-12");
        ServiceResult blocked = reviewService.issue("B-IMP-12", new IssueRequest("req-i7"));
        assertEquals(422, blocked.httpStatus());
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-IMP-12").status());

        // 已签发证据保持不变。
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-IMP-11").status());
        List<Map<String, Object>> events = customsService.listRiskEvents("CD-12");
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("CUSTOMS_REVOKED")));
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("RESERVATION_RELEASED")));
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("ISSUED_EVIDENCE_RETAINED_AFTER_REVOKE")));

        // 重复撤销业务拒绝。
        assertEquals(422, customsService.revoke(
                new CustomsRevokeRequest("req-v2", "CD-12", 4, OCCURRED)).httpStatus());
    }

    // ============================ 通关修订 ============================

    @Test
    void amendOnlyIncreasesAvailableQuotaAndKeepsHistory() {
        register("CD-13", 100);
        declareBatch("req-d15", "B-IMP-13");
        bind("req-b18", "CD-13", "B-IMP-13", 60, 1);

        // 不增加（等于或小于当前总数）皆拒绝。
        assertEquals(422, customsService.amend(
                new CustomsAmendRequest("req-a1", "CD-13", 100, 2, OCCURRED)).httpStatus());
        assertEquals(422, customsService.amend(
                new CustomsAmendRequest("req-a2", "CD-13", 80, 2, OCCURRED)).httpStatus());

        ServiceResult amended = customsService.amend(
                new CustomsAmendRequest("req-a3", "CD-13", 150, 2, OCCURRED));
        assertEquals(200, amended.httpStatus());
        CustomsDeclarationView view = customsService.getDeclaration("CD-13");
        assertEquals(150, view.totalQuantity());
        assertEquals(60, view.reservedQuantity());
        assertEquals(90, view.availableQuantity());
        assertEquals(3, view.version(), "成功变更版本 +1");
        assertEquals("合成生物制品厂", view.enterpriseName(), "修订不改企业品种");

        // 修订后余量可增加绑定。
        declareBatch("req-d16", "B-IMP-14");
        assertEquals(200, bind("req-b19", "CD-13", "B-IMP-14", 90, 3).httpStatus());

        // 撤销后不得修订。
        assertEquals(200, customsService.revoke(
                new CustomsRevokeRequest("req-v3", "CD-13", 4, OCCURRED)).httpStatus());
        assertEquals(422, customsService.amend(
                new CustomsAmendRequest("req-a4", "CD-13", 200, 5, OCCURRED)).httpStatus());
    }

    @Test
    void ledgerQueriesReturn404ForUnknownDeclaration() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> customsService.getDeclaration("CD-UNKNOWN"));
        assertEquals(404, ex.getHttpStatus());
        assertThrows(BusinessException.class,
                () -> customsService.listReservations("CD-UNKNOWN"));
        assertThrows(BusinessException.class,
                () -> customsService.listRiskEvents("CD-UNKNOWN"));
    }

    // ============================ 测试辅助 ============================

    private void register(String declarationNo, int totalQuantity) {
        register(declarationNo, totalQuantity, VALID_UNTIL);
    }

    private void register(String declarationNo, int totalQuantity, LocalDate validUntil) {
        ServiceResult result = customsService.register(new CustomsRegisterRequest(
                "req-r-" + declarationNo, declarationNo, "合成生物制品厂", "重组冠状病毒疫苗",
                validUntil, totalQuantity));
        assertEquals(200, result.httpStatus());
    }

    private void declareBatch(String requestId, String batchNo) {
        DeclareRequest request = declareRequest(requestId, batchNo, allDocuments(), boundaryPassingInspections());
        assertEquals(200, reviewService.declare(request).httpStatus());
    }

    private ServiceResult bind(String requestId, String declarationNo, String batchNo,
                               int quantity, int expectedVersion) {
        return customsService.bind(new CustomsBindRequest(
                requestId, declarationNo, batchNo, quantity, expectedVersion, OCCURRED));
    }

    private void toPublicityEffective(String batchNo) {
        assertEquals(200, reviewService.evaluate(batchNo).httpStatus());
        // 按实际公示期满日（UTC 日界）推进时钟，恰第 7 日期满生效。
        var view = reviewService.getReview(batchNo);
        clock.setFixed(view.publicityEndUtc().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertEquals(200, reviewService.settlePublicity(batchNo).httpStatus());
    }

    private void assertDeclaration(String declarationNo, int total, int reserved,
                                   int writtenOff, int available, int version) {
        CustomsDeclarationView view = customsService.getDeclaration(declarationNo);
        assertEquals(total, view.totalQuantity());
        assertEquals(reserved, view.reservedQuantity(), "预占数量");
        assertEquals(writtenOff, view.writtenOffQuantity(), "已核销数量");
        assertEquals(available, view.availableQuantity(), "剩余可预占");
        assertEquals(version, view.version(), "版本");
    }

    private void assertDeclarationRevoked(String declarationNo, int reserved, int writtenOff, int version) {
        CustomsDeclarationView view = customsService.getDeclaration(declarationNo);
        assertTrue(view.revoked());
        assertNotNull(view.revokedAt());
        assertEquals(reserved, view.reservedQuantity());
        assertEquals(writtenOff, view.writtenOffQuantity());
        assertEquals(version, view.version());
    }

    private void assertReservation(String batchNo, String status, int version) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT r.status, r.version FROM customs_reservation r
                JOIN batch_review b ON b.id = r.review_id WHERE b.batch_no = ?
                """, batchNo);
        assertEquals(status, row.get("status"));
        assertEquals(version, ((Number) row.get("version")).intValue());
    }

    private int countReservations() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customs_reservation", Integer.class);
        return count == null ? 0 : count;
    }
}
