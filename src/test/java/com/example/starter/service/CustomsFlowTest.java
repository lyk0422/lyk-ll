package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.catalog.DocumentCatalog;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.BindSubBatchRequest;
import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.RegisterDeclarationRequest;
import com.example.starter.dto.ReviseDeclarationRequest;
import com.example.starter.dto.RevokeDeclarationRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关单主流程与失败分支测试：
 * 登记 → 绑定预占 → 审查公示 → 签发原子核销；恰占满、超额、资料不符、
 * 恰截止、撤销阻断、撤回释放、版本冲突、修订边界。
 */
class CustomsFlowTest extends AbstractIntegrationTest {

    private static final Instant DECLARE_TIME = Instant.parse("2026-09-07T02:00:00Z");
    private static final Instant SETTLE_TIME = Instant.parse("2026-09-14T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");

    private RegisterDeclarationRequest registerRequest(String requestId, String declarationNo, int total) {
        return new RegisterDeclarationRequest(requestId, declarationNo,
                "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, total);
    }

    /** 申报并推进到公示期满生效（申报时刻 DECLARE_TIME，期满时刻 SETTLE_TIME）。 */
    private void driveToEffective(String batchNo) {
        clock.setFixed(DECLARE_TIME);
        reviewService.declare(declareRequest("d-" + batchNo, batchNo, allDocuments(),
                boundaryPassingInspections()));
        reviewService.evaluate(batchNo);
        clock.setFixed(SETTLE_TIME);
        reviewService.settlePublicity(batchNo);
    }

    @Test
    void registerBindAndIssueConsumesReservationAtomically() {
        clock.setFixed(DECLARE_TIME);
        ServiceResult registered = customsService.register(registerRequest("reg-1", "CUS-1", 100));
        assertEquals(200, registered.httpStatus());
        CustomsDeclarationView view = (CustomsDeclarationView) registered.body();
        assertEquals(1, view.version(), "登记后版本从 1 开始");
        assertEquals(100, view.remainingQuantity());
        assertEquals(0, view.reservedQuantity());
        assertEquals(0, view.consumedQuantity());

        // 绑定预占 40 支，版本 +1
        driveToEffective("B-CUS-1");
        ServiceResult bound = customsService.bind("CUS-1",
                new BindSubBatchRequest("bind-1", "B-CUS-1", 40, 1));
        assertEquals(200, bound.httpStatus());
        CustomsDeclarationView boundView = (CustomsDeclarationView) bound.body();
        assertEquals(2, boundView.version(), "绑定成功后版本加 1");
        assertEquals(40, boundView.reservedQuantity());
        assertEquals(60, boundView.remainingQuantity());
        assertEquals("RESERVED", boundView.bindings().get(0).status());

        // 签发：原子把预占转已核销，不能已发证而余量未扣
        clock.setFixed(SETTLE_TIME);
        ServiceResult issued = reviewService.issue("B-CUS-1", new IssueRequest("issue-1"));
        assertEquals(200, issued.httpStatus());
        assertEquals(ReviewStatus.ISSUED.name(), asView(issued).status());

        CustomsDeclarationView after = customsService.getDeclaration("CUS-1");
        assertEquals(0, after.reservedQuantity(), "签发后预占清零");
        assertEquals(40, after.consumedQuantity(), "签发后预占转已核销");
        assertEquals(60, after.remainingQuantity());
        assertEquals(3, after.version(), "核销成功后版本加 1");
        assertEquals("CONSUMED", after.bindings().get(0).status());
        assertNotNull(after.bindings().get(0).consumedAt());
    }

    @Test
    void bindingExactlyFillingTotalIsLegalAndOverflowIsRejected() {
        clock.setFixed(DECLARE_TIME);
        customsService.register(registerRequest("reg-2", "CUS-2", 100));
        driveToEffective("B-CUS-2A");
        driveToEffective("B-CUS-2B");

        ServiceResult first = customsService.bind("CUS-2",
                new BindSubBatchRequest("bind-2a", "B-CUS-2A", 60, 1));
        assertEquals(200, first.httpStatus());

        // 恰占满：60 + 40 = 100，合法
        ServiceResult exact = customsService.bind("CUS-2",
                new BindSubBatchRequest("bind-2b", "B-CUS-2B", 40, 2));
        assertEquals(200, exact.httpStatus(), "恰占满必须合法");
        CustomsDeclarationView full = (CustomsDeclarationView) exact.body();
        assertEquals(0, full.remainingQuantity());
        assertEquals(100, full.reservedQuantity());

        // 再绑任何正数都超额
        driveToEffective("B-CUS-2C");
        ServiceResult overflow = customsService.bind("CUS-2",
                new BindSubBatchRequest("bind-2c", "B-CUS-2C", 1, 3));
        assertEquals(422, overflow.httpStatus());
    }

    @Test
    void bindingRejectsMismatchTerminalAndDuplicate() {
        clock.setFixed(DECLARE_TIME);
        customsService.register(registerRequest("reg-3", "CUS-3", 100));
        driveToEffective("B-CUS-3A");

        // 企业品种不符：整笔拒绝
        customsService.register(new RegisterDeclarationRequest("reg-3b", "CUS-3-OTHER",
                "另一家企业", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        ServiceResult mismatch = customsService.bind("CUS-3-OTHER",
                new BindSubBatchRequest("bind-3a", "B-CUS-3A", 10, 1));
        assertEquals(422, mismatch.httpStatus());
        assertEquals(0, customsService.getDeclaration("CUS-3-OTHER").reservedQuantity(),
                "资料不符整笔拒绝，不得产生预占");

        // 正常绑定后，同一子批不能绑第二张单
        assertEquals(200, customsService.bind("CUS-3",
                new BindSubBatchRequest("bind-3b", "B-CUS-3A", 10, 1)).httpStatus());
        ServiceResult duplicate = customsService.bind("CUS-3",
                new BindSubBatchRequest("bind-3c", "B-CUS-3A", 10, 2));
        assertEquals(422, duplicate.httpStatus());

        // 不存在的通关单 / 不存在的批号
        ServiceResult noDeclaration = customsService.bind("CUS-NONE",
                new BindSubBatchRequest("bind-3d", "B-CUS-3A", 10, 1));
        assertEquals(404, noDeclaration.httpStatus());
        ServiceResult noBatch = customsService.bind("CUS-3",
                new BindSubBatchRequest("bind-3e", "B-NONE", 10, 2));
        assertEquals(404, noBatch.httpStatus());

        // 已签发终态批次不能绑定
        clock.setFixed(SETTLE_TIME);
        reviewService.issue("B-CUS-3A", new IssueRequest("issue-3a"));
        driveToEffective("B-CUS-3B");
        reviewService.issue("B-CUS-3B", new IssueRequest("issue-3b"));
        ServiceResult terminal = customsService.bind("CUS-3",
                new BindSubBatchRequest("bind-3f", "B-CUS-3B", 10, 3));
        assertEquals(422, terminal.httpStatus());
    }

    @Test
    void issueAtExactValidityDeadlineIsRejected() {
        clock.setFixed(DECLARE_TIME);
        // 通关单截止恰为签发时刻
        customsService.register(new RegisterDeclarationRequest("reg-4", "CUS-4",
                "合成生物制品厂", "重组冠状病毒疫苗", SETTLE_TIME, 100));
        driveToEffective("B-CUS-4");
        assertEquals(200, customsService.bind("CUS-4",
                new BindSubBatchRequest("bind-4", "B-CUS-4", 10, 1)).httpStatus());

        // now == 截止：恰截止拒绝
        clock.setFixed(SETTLE_TIME);
        ServiceResult issued = reviewService.issue("B-CUS-4", new IssueRequest("issue-4"));
        assertEquals(422, issued.httpStatus());
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(),
                reviewService.getReview("B-CUS-4").status(), "签发被拒绝不得改变批次状态");
        CustomsDeclarationView view = customsService.getDeclaration("CUS-4");
        assertEquals(10, view.reservedQuantity(), "签发被拒绝不得核销预占");
        assertEquals(0, view.consumedQuantity());

        // 截止前一瞬签发成功
        clock.setFixed(SETTLE_TIME.minusSeconds(1));
        ServiceResult beforeDeadline = reviewService.issue("B-CUS-4", new IssueRequest("issue-4b"));
        assertEquals(200, beforeDeadline.httpStatus(), "now<截止必须有效");
        assertEquals(10, customsService.getDeclaration("CUS-4").consumedQuantity());
    }

    @Test
    void revokeReleasesReservedBlocksIssueAndKeepsIssuedEvidenceWithRiskEvent() {
        clock.setFixed(DECLARE_TIME);
        customsService.register(registerRequest("reg-5", "CUS-5", 100));
        driveToEffective("B-CUS-5A");
        driveToEffective("B-CUS-5B");
        customsService.bind("CUS-5", new BindSubBatchRequest("bind-5a", "B-CUS-5A", 30, 1));
        customsService.bind("CUS-5", new BindSubBatchRequest("bind-5b", "B-CUS-5B", 20, 2));

        // 先签发 A（核销 30）
        clock.setFixed(SETTLE_TIME);
        assertEquals(200, reviewService.issue("B-CUS-5A", new IssueRequest("issue-5a")).httpStatus());
        CustomsDeclarationView beforeRevoke = customsService.getDeclaration("CUS-5");
        assertEquals(20, beforeRevoke.reservedQuantity());
        assertEquals(30, beforeRevoke.consumedQuantity());

        // 撤销：B 的预占释放，A 的已签发证据不变，记风险事件
        ServiceResult revoked = customsService.revoke("CUS-5",
                new RevokeDeclarationRequest("revoke-5", 4));
        assertEquals(200, revoked.httpStatus());
        CustomsDeclarationView afterRevoke = (CustomsDeclarationView) revoked.body();
        assertTrue(afterRevoke.revoked());
        assertEquals(0, afterRevoke.reservedQuantity(), "未签发子批预占全部释放");
        assertEquals(30, afterRevoke.consumedQuantity(), "已签发核销保持不变");
        assertEquals(5, afterRevoke.version());
        assertEquals("RELEASED", afterRevoke.bindings().get(1).status());
        assertEquals("DECLARATION_REVOKED", afterRevoke.bindings().get(1).releaseReason());
        assertEquals("CONSUMED", afterRevoke.bindings().get(0).status(), "已签发证据保持不变");

        long riskEvents = customsService.listEvents("CUS-5").stream()
                .filter(e -> e.get("eventType").equals("RISK_ISSUED_UNDER_REVOKED")).count();
        assertEquals(1, riskEvents, "每个已核销子批记一条风险事件");

        // 撤销阻断签发：B 已公示生效也不能签发（不得以先前公示通过绕过新失效）
        ServiceResult blocked = reviewService.issue("B-CUS-5B", new IssueRequest("issue-5b"));
        assertEquals(422, blocked.httpStatus());
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(),
                reviewService.getReview("B-CUS-5B").status());

        // 重复撤销（不同 requestId）业务拒绝
        ServiceResult again = customsService.revoke("CUS-5", new RevokeDeclarationRequest("revoke-5b", 5));
        assertEquals(422, again.httpStatus());
    }

    @Test
    void overdueCorrectionWithdrawalReleasesReservationExactlyOnce() {
        // 周一申报缺项，进入待补正
        clock.setFixed(DECLARE_TIME);
        List<String> docs = new ArrayList<>(allDocuments());
        docs.remove(DocumentCatalog.LABEL_INSERT);
        reviewService.declare(declareRequest("d-w1", "B-CUS-6", docs, boundaryPassingInspections()));
        customsService.register(registerRequest("reg-6", "CUS-6", 100));
        assertEquals(200, customsService.bind("CUS-6",
                new BindSubBatchRequest("bind-6", "B-CUS-6", 25, 1)).httpStatus());
        assertEquals(25, customsService.getDeclaration("CUS-6").reservedQuantity());

        // 逾期未补正：按撤回处理，预占释放
        clock.setFixed(Instant.parse("2026-09-15T01:00:00Z"));
        ServiceResult late = reviewService.correct("B-CUS-6",
                new CorrectRequest("c-w1", List.of(DocumentCatalog.LABEL_INSERT)));
        assertEquals(400, late.httpStatus());
        assertEquals(ReviewStatus.WITHDRAWN.name(), reviewService.getReview("B-CUS-6").status());

        CustomsDeclarationView released = customsService.getDeclaration("CUS-6");
        assertEquals(0, released.reservedQuantity(), "撤回必须释放预占");
        assertEquals(0, released.consumedQuantity());
        assertEquals(100, released.remainingQuantity());
        assertEquals("RELEASED", released.bindings().get(0).status());
        assertEquals("SUB_BATCH_WITHDRAWN", released.bindings().get(0).releaseReason());

        // 重复补正触发状态拒绝，不会再次释放（只释放一次）
        ServiceResult repeat = reviewService.correct("B-CUS-6",
                new CorrectRequest("c-w2", List.of(DocumentCatalog.LABEL_INSERT)));
        assertEquals(409, repeat.httpStatus());
        CustomsDeclarationView unchanged = customsService.getDeclaration("CUS-6");
        assertEquals(0, unchanged.reservedQuantity(), "预占只释放一次，不得重复释放");
        assertEquals(100, unchanged.remainingQuantity());
        assertEquals(released.version(), unchanged.version(), "重复撤回不得再次变更版本");
    }

    @Test
    void revisionOnlyIncreasesUnreservedAllowance() {
        clock.setFixed(DECLARE_TIME);
        customsService.register(registerRequest("reg-7", "CUS-7", 100));
        driveToEffective("B-CUS-7");
        customsService.bind("CUS-7", new BindSubBatchRequest("bind-7", "B-CUS-7", 60, 1));

        // 调减拒绝、持平拒绝
        ServiceResult decrease = customsService.revise("CUS-7",
                new ReviseDeclarationRequest("revise-7a", 2, 50));
        assertEquals(422, decrease.httpStatus());
        ServiceResult same = customsService.revise("CUS-7",
                new ReviseDeclarationRequest("revise-7b", 2, 100));
        assertEquals(422, same.httpStatus());

        // 调增成功：余量增加，历史预占与企业品种不变
        ServiceResult increased = customsService.revise("CUS-7",
                new ReviseDeclarationRequest("revise-7c", 2, 150));
        assertEquals(200, increased.httpStatus());
        CustomsDeclarationView view = (CustomsDeclarationView) increased.body();
        assertEquals(150, view.totalQuantity());
        assertEquals(60, view.reservedQuantity());
        assertEquals(90, view.remainingQuantity());
        assertEquals(3, view.version());
        assertEquals("合成生物制品厂", view.enterpriseName());
        assertEquals("重组冠状病毒疫苗", view.productName());
    }

    @Test
    void expectedVersionMismatchConflicts() {
        clock.setFixed(DECLARE_TIME);
        customsService.register(registerRequest("reg-8", "CUS-8", 100));
        driveToEffective("B-CUS-8");

        ServiceResult stale = customsService.bind("CUS-8",
                new BindSubBatchRequest("bind-8", "B-CUS-8", 10, 5));
        assertEquals(409, stale.httpStatus(), "expectedVersion 与当前版本不符必须 409");
        assertEquals(0, customsService.getDeclaration("CUS-8").reservedQuantity(),
                "版本冲突不得产生预占");

        ServiceResult staleRevoke = customsService.revoke("CUS-8",
                new RevokeDeclarationRequest("revoke-8", 3));
        assertEquals(409, staleRevoke.httpStatus());
        assertFalse(customsService.getDeclaration("CUS-8").revoked());
    }

    @Test
    void domesticBatchWithoutBindingIssuesWithoutCustomsGate() {
        driveToEffective("B-DOMESTIC-1");
        clock.setFixed(SETTLE_TIME);
        ServiceResult issued = reviewService.issue("B-DOMESTIC-1", new IssueRequest("issue-d1"));
        assertEquals(200, issued.httpStatus(), "国内批次不新增通关门禁");
        assertEquals(ReviewStatus.ISSUED.name(), asView(issued).status());
    }

    @Test
    void declarationNotFoundReturns404() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> customsService.getDeclaration("CUS-NONE"));
        assertEquals(404, ex.getHttpStatus());
    }
}
