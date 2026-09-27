package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReviseRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关单登记、绑定、核销主流程与失败分支测试（H2 内存库真实事务）。
 */
class CustomsFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T_SETTLE = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T23:59:59Z");
    private static final String ENTERPRISE = "合成生物制品厂";
    private static final String PRODUCT = "重组冠状病毒疫苗";

    @Autowired
    private CustomsService customsService;

    // ============================ 登记 ============================

    @Test
    void registerCreatesDeclarationWithVersionOne() {
        clock.setFixed(T0);
        ServiceResult result = customsService.register(registerRequest("reg-1", "CIQ-001", 100));
        assertEquals(200, result.httpStatus());
        CustomsDeclarationView view = asCustomsView(result);
        assertEquals("CIQ-001", view.declarationNo());
        assertEquals(ENTERPRISE, view.enterpriseName());
        assertEquals(PRODUCT, view.productName());
        assertEquals(100, view.totalDoses());
        assertEquals(0, view.reservedDoses());
        assertEquals(0, view.consumedDoses());
        assertEquals(100, view.availableDoses());
        assertEquals("ACTIVE", view.status());
        assertEquals(1, view.version(), "版本必须从 1 开始");

        CustomsDeclarationView ledger = customsService.getLedger("CIQ-001");
        assertEquals(1, ledger.version());
        assertEquals(100, ledger.availableDoses());

        ServiceResult duplicate = customsService.register(registerRequest("reg-2", "CIQ-001", 50));
        assertEquals(409, duplicate.httpStatus(), "重复通关单号必须 409");
    }

    @Test
    void registerRejectsPastOrExactDeadline() {
        clock.setFixed(T0);
        ServiceResult exact = customsService.register(new CustomsRegisterRequest(
                "reg-exp-1", "CIQ-EXP-1", ENTERPRISE, PRODUCT, T0, 10));
        assertEquals(422, exact.httpStatus(), "恰截止时刻登记视为已失效");
        ServiceResult past = customsService.register(new CustomsRegisterRequest(
                "reg-exp-2", "CIQ-EXP-2", ENTERPRISE, PRODUCT, T0.minusSeconds(1), 10));
        assertEquals(422, past.httpStatus(), "截止早于当前时刻必须拒绝");
    }

    // ============================ 绑定（预占） ============================

    @Test
    void bindReservesQuotaAndExactlyFullIsLegal() {
        clock.setFixed(T0);
        declareBatch("d-b1", "B-BIND-1");
        declareBatch("d-b2", "B-BIND-2");
        declareBatch("d-b3", "B-BIND-3");
        customsService.register(registerRequest("reg-b", "CIQ-BIND", 100));

        ServiceResult first = customsService.bind("CIQ-BIND", new CustomsBindRequest("bind-1", 1, "B-BIND-1", 40));
        assertEquals(200, first.httpStatus());
        CustomsDeclarationView view = asCustomsView(first);
        assertEquals(40, view.reservedDoses());
        assertEquals(60, view.availableDoses());
        assertEquals(2, view.version(), "成功绑定版本加 1");
        assertEquals(1, view.bindings().size());
        assertEquals("B-BIND-1", view.bindings().get(0).batchNo());
        assertEquals("RESERVED", view.bindings().get(0).status());

        ServiceResult second = customsService.bind("CIQ-BIND", new CustomsBindRequest("bind-2", 2, "B-BIND-2", 60));
        assertEquals(200, second.httpStatus(), "恰占满必须合法");
        assertEquals(100, asCustomsView(second).reservedDoses());
        assertEquals(0, asCustomsView(second).availableDoses());

        ServiceResult overflow = customsService.bind("CIQ-BIND", new CustomsBindRequest("bind-3", 3, "B-BIND-3", 1));
        assertEquals(422, overflow.httpStatus(), "超出未占余量必须整笔拒绝");
        assertEquals(3, customsService.getLedger("CIQ-BIND").version(), "失败不变更版本");
    }

    @Test
    void bindRejectsMismatchNotFoundAndVersionConflict() {
        clock.setFixed(T0);
        declareBatch("d-m1", "B-MIS-1");
        declareBatch("d-m2", "B-MIS-2");
        customsService.register(registerRequest("reg-m", "CIQ-MIS", 100));
        customsService.register(new CustomsRegisterRequest(
                "reg-m2", "CIQ-MIS-2", "另一企业", PRODUCT, VALID_UNTIL, 100));
        customsService.register(registerRequest("reg-m3", "CIQ-MIS-3", 100));

        ServiceResult enterpriseMismatch = customsService.bind("CIQ-MIS-2",
                new CustomsBindRequest("bind-m1", 1, "B-MIS-1", 10));
        assertEquals(422, enterpriseMismatch.httpStatus(), "企业品种不符整笔拒绝");

        ServiceResult noDeclaration = customsService.bind("CIQ-NONE",
                new CustomsBindRequest("bind-m2", 1, "B-MIS-1", 10));
        assertEquals(404, noDeclaration.httpStatus());

        ServiceResult noBatch = customsService.bind("CIQ-MIS",
                new CustomsBindRequest("bind-m3", 1, "B-NONE", 10));
        assertEquals(404, noBatch.httpStatus());

        ServiceResult versionConflict = customsService.bind("CIQ-MIS",
                new CustomsBindRequest("bind-m4", 7, "B-MIS-1", 10));
        assertEquals(409, versionConflict.httpStatus(), "期望版本不符必须 409");

        ServiceResult bound = customsService.bind("CIQ-MIS", new CustomsBindRequest("bind-m5", 1, "B-MIS-1", 10));
        assertEquals(200, bound.httpStatus());
        ServiceResult rebound = customsService.bind("CIQ-MIS",
                new CustomsBindRequest("bind-m6", 2, "B-MIS-1", 10));
        assertEquals(422, rebound.httpStatus(), "一个子批只能绑定一张单");
        ServiceResult otherDeclaration = customsService.bind("CIQ-MIS-3",
                new CustomsBindRequest("bind-m7", 1, "B-MIS-1", 10));
        assertEquals(422, otherDeclaration.httpStatus(), "已绑定子批不能再绑其他通关单");
    }

    @Test
    void bindRejectsExpiredDeclarationAtExactDeadline() {
        clock.setFixed(T0);
        declareBatch("d-e1", "B-EXP-1");
        Instant deadline = T0.plusSeconds(3600);
        customsService.register(new CustomsRegisterRequest(
                "reg-e1", "CIQ-EXP-B", ENTERPRISE, PRODUCT, deadline, 100));

        clock.setFixed(deadline);
        ServiceResult exact = customsService.bind("CIQ-EXP-B",
                new CustomsBindRequest("bind-e1", 1, "B-EXP-1", 10));
        assertEquals(422, exact.httpStatus(), "恰截止必须拒绝绑定");

        clock.setFixed(deadline.plusSeconds(1));
        ServiceResult after = customsService.bind("CIQ-EXP-B",
                new CustomsBindRequest("bind-e2", 1, "B-EXP-1", 10));
        assertEquals(422, after.httpStatus(), "截止后必须拒绝绑定");
        assertEquals(0, customsService.getLedger("CIQ-EXP-B").reservedDoses(), "失败不得产生预占");
    }

    // ============================ 签发核销 ============================

    @Test
    void importIssueConsumesReservationAtomically() {
        driveToEffective("B-ISS-1", "iss1");
        clock.setFixed(T_SETTLE);
        customsService.register(registerRequest("reg-i1", "CIQ-ISS-1", 100));
        ServiceResult bound = customsService.bind("CIQ-ISS-1", new CustomsBindRequest("bind-i1", 1, "B-ISS-1", 50));
        assertEquals(200, bound.httpStatus());

        ServiceResult issued = reviewService.issue("B-ISS-1", new IssueRequest("issue-i1"));
        assertEquals(200, issued.httpStatus());

        CustomsDeclarationView ledger = customsService.getLedger("CIQ-ISS-1");
        assertEquals(0, ledger.reservedDoses(), "签发后预占必须清零");
        assertEquals(50, ledger.consumedDoses(), "签发原子把预占转已核销");
        assertEquals(50, ledger.availableDoses());
        assertEquals(3, ledger.version(), "登记/绑定/核销各加 1");
        assertEquals("CONSUMED", ledger.bindings().get(0).status());
        assertNotNull(ledger.bindings().get(0).consumedAt());
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-ISS-1").status());

        List<Map<String, Object>> events = reviewService.listEvents("B-ISS-1");
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("CUSTOMS_CONSUMED")),
                "签发核销必须留痕");
    }

    @Test
    void issueAtExactDeadlineIsRejectedAndReservationKept() {
        driveToEffective("B-ISS-2", "iss2");
        Instant deadline = T_SETTLE.plusSeconds(7200);
        clock.setFixed(T_SETTLE);
        customsService.register(new CustomsRegisterRequest(
                "reg-i2", "CIQ-ISS-2", ENTERPRISE, PRODUCT, deadline, 100));
        customsService.bind("CIQ-ISS-2", new CustomsBindRequest("bind-i2", 1, "B-ISS-2", 50));

        clock.setFixed(deadline);
        ServiceResult exact = reviewService.issue("B-ISS-2", new IssueRequest("issue-i2"));
        assertEquals(422, exact.httpStatus(), "恰截止必须拒绝签发");
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-ISS-2").status(),
                "签发被拒不得改变审查状态");
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-ISS-2");
        assertEquals(50, ledger.reservedDoses(), "签发失败不得核销预占");
        assertEquals(0, ledger.consumedDoses());
    }

    @Test
    void domesticBatchIssuesWithoutCustomsGate() {
        driveToEffective("B-DOM-1", "dom1");
        ServiceResult issued = reviewService.issue("B-DOM-1", new IssueRequest("issue-dom1"));
        assertEquals(200, issued.httpStatus(), "国内批次不新增通关门禁");
    }

    // ============================ 撤回 ============================

    @Test
    void withdrawReleasesReservationExactlyOnce() {
        driveToEffective("B-WD-1", "wd1");
        clock.setFixed(T_SETTLE);
        customsService.register(registerRequest("reg-w1", "CIQ-WD-1", 100));
        customsService.bind("CIQ-WD-1", new CustomsBindRequest("bind-w1", 1, "B-WD-1", 30));

        ServiceResult wrongVersion = customsService.withdraw("CIQ-WD-1", "B-WD-1",
                new CustomsWithdrawRequest("wd-w1", 9));
        assertEquals(409, wrongVersion.httpStatus());

        ServiceResult withdrawn = customsService.withdraw("CIQ-WD-1", "B-WD-1",
                new CustomsWithdrawRequest("wd-w2", 2));
        assertEquals(200, withdrawn.httpStatus());
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-WD-1");
        assertEquals(0, ledger.reservedDoses(), "撤回必须释放预占");
        assertEquals(100, ledger.availableDoses());
        assertEquals(3, ledger.version());
        assertEquals("RELEASED", ledger.bindings().get(0).status());
        assertEquals("WITHDRAWN", ledger.bindings().get(0).releaseReason());

        ServiceResult again = customsService.withdraw("CIQ-WD-1", "B-WD-1",
                new CustomsWithdrawRequest("wd-w3", 3));
        assertEquals(422, again.httpStatus(), "预占只释放一次");
        assertEquals(3, customsService.getLedger("CIQ-WD-1").version(), "重复释放不得变更版本");

        // 撤回后按国内批次处理：签发不再受通关门禁
        ServiceResult issued = reviewService.issue("B-WD-1", new IssueRequest("issue-w1"));
        assertEquals(200, issued.httpStatus());
        assertEquals(0, customsService.getLedger("CIQ-WD-1").consumedDoses(), "已撤回子批签发不核销通关量");
    }

    // ============================ 撤销 ============================

    @Test
    void revokeReleasesUnissuedBlocksIssueAndKeepsIssuedEvidence() {
        driveToEffective("B-RV-1", "rv1");
        driveToEffective("B-RV-2", "rv2");
        clock.setFixed(T_SETTLE);
        customsService.register(registerRequest("reg-r1", "CIQ-RV-1", 100));
        customsService.bind("CIQ-RV-1", new CustomsBindRequest("bind-r1", 1, "B-RV-1", 40));
        customsService.bind("CIQ-RV-1", new CustomsBindRequest("bind-r2", 2, "B-RV-2", 30));

        // 先签发 B-RV-1：核销 40
        ServiceResult issued = reviewService.issue("B-RV-1", new IssueRequest("issue-r1"));
        assertEquals(200, issued.httpStatus());

        ServiceResult revoked = customsService.revoke("CIQ-RV-1", new CustomsRevokeRequest("rvk-1", 4));
        assertEquals(200, revoked.httpStatus());
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-RV-1");
        assertEquals("REVOKED", ledger.status());
        assertEquals(0, ledger.reservedDoses(), "未签发子批预占必须全部释放");
        assertEquals(40, ledger.consumedDoses(), "已签发核销保持不变");
        assertEquals(5, ledger.version());

        ServiceResult blocked = reviewService.issue("B-RV-2", new IssueRequest("issue-r2"));
        assertEquals(422, blocked.httpStatus(), "撤销后未签发子批必须阻断签发");
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-RV-2").status());
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-RV-1").status(),
                "已签发证据保持不变");

        List<Map<String, Object>> issuedEvents = reviewService.listEvents("B-RV-1");
        assertTrue(issuedEvents.stream().anyMatch(e ->
                e.get("eventType").equals("CUSTOMS_RISK_ISSUED_UNDER_REVOKED")), "已签发批次必须记风险事件");
        List<Map<String, Object>> blockedEvents = reviewService.listEvents("B-RV-2");
        assertTrue(blockedEvents.stream().anyMatch(e -> e.get("eventType").equals("CUSTOMS_RELEASED")),
                "被撤销释放的子批必须留痕");

        ServiceResult again = customsService.revoke("CIQ-RV-1", new CustomsRevokeRequest("rvk-2", 5));
        assertEquals(422, again.httpStatus(), "重复撤销必须拒绝");

        ServiceResult bindAfterRevoke = customsService.bind("CIQ-RV-1",
                new CustomsBindRequest("bind-r3", 5, "B-RV-2", 10));
        assertEquals(422, bindAfterRevoke.httpStatus(), "撤销后不能再绑定");
    }

    @Test
    void revokedSubBatchCannotRebindToAnotherDeclaration() {
        driveToEffective("B-RV-3", "rv3");
        clock.setFixed(T_SETTLE);
        customsService.register(registerRequest("reg-r3", "CIQ-RV-3A", 100));
        customsService.register(registerRequest("reg-r3b", "CIQ-RV-3B", 100));
        customsService.bind("CIQ-RV-3A", new CustomsBindRequest("bind-r5", 1, "B-RV-3", 10));
        customsService.revoke("CIQ-RV-3A", new CustomsRevokeRequest("rvk-3", 2));

        ServiceResult rebind = customsService.bind("CIQ-RV-3B",
                new CustomsBindRequest("bind-r6", 1, "B-RV-3", 10));
        assertEquals(422, rebind.httpStatus(), "被撤销释放的子批不能重新绑定其他通关单");

        ServiceResult blocked = reviewService.issue("B-RV-3", new IssueRequest("issue-r3"));
        assertEquals(422, blocked.httpStatus(), "被撤销子批签发必须阻断");
    }

    // ============================ 修订 ============================

    @Test
    void reviseOnlyIncreasesUnoccupiedQuota() {
        clock.setFixed(T0);
        declareBatch("d-rev1", "B-REV-1");
        customsService.register(registerRequest("reg-rev1", "CIQ-REV-1", 100));
        customsService.bind("CIQ-REV-1", new CustomsBindRequest("bind-rev1", 1, "B-REV-1", 60));

        ServiceResult equal = customsService.revise("CIQ-REV-1", new CustomsReviseRequest("rev-1", 2, 100));
        assertEquals(422, equal.httpStatus(), "修订不增必须拒绝");
        ServiceResult decrease = customsService.revise("CIQ-REV-1", new CustomsReviseRequest("rev-2", 2, 80));
        assertEquals(422, decrease.httpStatus(), "修订调减必须拒绝");

        ServiceResult increased = customsService.revise("CIQ-REV-1", new CustomsReviseRequest("rev-3", 2, 150));
        assertEquals(200, increased.httpStatus());
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-REV-1");
        assertEquals(150, ledger.totalDoses());
        assertEquals(60, ledger.reservedDoses(), "修订不改历史预占");
        assertEquals(90, ledger.availableDoses(), "修订仅增加未占余量");
        assertEquals(ENTERPRISE, ledger.enterpriseName());
        assertEquals(3, ledger.version());

        customsService.revoke("CIQ-REV-1", new CustomsRevokeRequest("rvk-rev1", 3));
        ServiceResult reviseRevoked = customsService.revise("CIQ-REV-1",
                new CustomsReviseRequest("rev-4", 4, 200));
        assertEquals(422, reviseRevoked.httpStatus(), "已撤销通关单不能修订");
    }

    // ============================ 幂等 ============================

    @Test
    void idempotencyReplaysFrozenResultAndRejectsDifferentParams() {
        clock.setFixed(T0);
        CustomsRegisterRequest request = registerRequest("idem-reg", "CIQ-IDEM-1", 100);
        ServiceResult first = customsService.register(request);
        ServiceResult replay = customsService.register(request);
        assertEquals(200, replay.httpStatus());
        assertEquals(jsonSupport.write(first.body()), jsonSupport.write(replay.body()),
                "同键同参必须重放首次冻结结果");
        assertEquals(1, customsService.getLedger("CIQ-IDEM-1").version(), "重放不得重复变更");

        ServiceResult conflict = customsService.register(new CustomsRegisterRequest(
                "idem-reg", "CIQ-IDEM-2", ENTERPRISE, PRODUCT, VALID_UNTIL, 100));
        assertEquals(409, conflict.httpStatus(), "同键异参必须 409");
    }

    @Test
    void failedOperationDoesNotOccupyIdempotencyKey() {
        clock.setFixed(T0);
        declareBatch("d-f1", "B-FAIL-1");
        customsService.register(registerRequest("reg-f1", "CIQ-FAIL-1", 100));

        ServiceResult rejected = customsService.bind("CIQ-FAIL-1",
                new CustomsBindRequest("idem-bind-fail", 1, "B-FAIL-1", 200));
        assertEquals(422, rejected.httpStatus(), "超量绑定必须拒绝");

        ServiceResult retry = customsService.bind("CIQ-FAIL-1",
                new CustomsBindRequest("idem-bind-fail", 1, "B-FAIL-1", 60));
        assertEquals(200, retry.httpStatus(), "失败不占键，同键修正参数后必须可重新执行");
        assertEquals(60, customsService.getLedger("CIQ-FAIL-1").reservedDoses());
    }

    // ============================ 内部辅助 ============================

    private CustomsRegisterRequest registerRequest(String requestId, String declarationNo, int totalDoses) {
        return new CustomsRegisterRequest(requestId, declarationNo, ENTERPRISE, PRODUCT, VALID_UNTIL, totalDoses);
    }

    private void declareBatch(String requestId, String batchNo) {
        clock.setFixed(T0);
        ServiceResult result = reviewService.declare(declareRequest(requestId, batchNo,
                allDocuments(), boundaryPassingInspections()));
        assertEquals(200, result.httpStatus());
    }

    /** 走完申报→评定→公示期满生效，时钟停在期满日。 */
    private void driveToEffective(String batchNo, String requestIdPrefix) {
        declareBatch(requestIdPrefix + "-d", batchNo);
        clock.setFixed(T0);
        assertEquals(200, reviewService.evaluate(batchNo).httpStatus());
        clock.setFixed(T_SETTLE);
        assertEquals(200, reviewService.settlePublicity(batchNo).httpStatus());
    }

    private CustomsDeclarationView asCustomsView(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), CustomsDeclarationView.class);
    }
}
