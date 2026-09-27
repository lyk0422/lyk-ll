package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.dto.SubbatchWithdrawRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关单核销衔接主流程与失败分支 H2 测试：
 * 登记 → 多子批预占（恰占满/超占拒绝）→ 企业品种校验 → 签发原子转核销 →
 * 撤回只释放一次 → 撤销释放未签发并保留已签发证据+风险事件 → 修订仅增余量。
 */
class CustomsFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-10-01T00:00:00Z");

    private CustomsRegisterRequest registerRequest(String requestId, String customsNo, int total) {
        return new CustomsRegisterRequest(requestId, customsNo,
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, total, T0);
    }

    private CustomsBindRequest bindRequest(String requestId, String customsNo, String batchNo,
                                           int qty, int expectedVersion) {
        return new CustomsBindRequest(requestId, customsNo, batchNo, qty, expectedVersion, T0);
    }

    /** 申报+评定（公示期 2026-09-01 至 2026-09-08 UTC），并在 ISSUE_AT 公示期满生效。 */
    private void makeIssueable(String declareId, String batchNo) {
        clock.setFixed(T0);
        prepareIssueableImportBatch(declareId, batchNo);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity(batchNo);
    }

    /** 断言新写操作抛出业务异常（失败回滚，不占幂等键）。 */
    private void assertBusiness(org.junit.jupiter.api.function.Executable action, int status, String code) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(status, ex.getHttpStatus());
        assertEquals(code, ex.getError());
    }

    /** 从 ServiceResult 失败体提取错误码（首次为 ErrorResponse，重放为 Map，统一转换）。 */
    private String errorCode(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), ErrorResponse.class).error();
    }

    @Test
    void registerStartsAtVersionOneAndDuplicateRejected() {
        ServiceResult created = customsService.register(registerRequest("reg-1", "CI-DUP", 100));
        assertEquals(200, created.httpStatus());
        CustomsLedgerView view = (CustomsLedgerView) created.body();
        assertEquals(1, view.version());
        assertEquals(100, view.totalQuantity());
        assertEquals(100, view.availableQuantity());
        assertEquals("ACTIVE", view.status());

        // 同号再登记：业务拒绝 409（异常回滚，不占 reg-2 键）
        assertBusiness(() -> customsService.register(registerRequest("reg-2", "CI-DUP", 50)),
                409, "CUSTOMS_NO_TAKEN");
        assertEquals(100, customsService.getLedger("CI-DUP").totalQuantity(), "重复登记不得改变原台账");
    }

    @Test
    void multipleSubbatchesReserveUpToTotalAndExactlyFullIsLegal() {
        customsService.register(registerRequest("reg-cap", "CI-CAP", 100));
        makeIssueable("d-cap-1", "B-CAP-1");
        makeIssueable("d-cap-2", "B-CAP-2");
        makeIssueable("d-cap-3", "B-CAP-3");

        ServiceResult b1 = customsService.bind(bindRequest("bind-cap-1", "CI-CAP", "B-CAP-1", 60, 1));
        assertEquals(200, b1.httpStatus());
        assertEquals(2, ((CustomsLedgerView) b1.body()).version(), "成功绑定后通关单版本加 1");

        // 再占 40：恰占满合法
        ServiceResult b2 = customsService.bind(bindRequest("bind-cap-2", "CI-CAP", "B-CAP-2", 40, 2));
        assertEquals(200, b2.httpStatus());
        CustomsLedgerView full = (CustomsLedgerView) b2.body();
        assertEquals(100, full.reservedQuantity());
        assertEquals(0, full.availableQuantity(), "恰占满时余量为 0，合法");

        // 再占 1：超过总数，整笔 422
        assertBusiness(() -> customsService.bind(bindRequest("bind-cap-3", "CI-CAP", "B-CAP-3", 1, 3)),
                422, "INSUFFICIENT_CUSTOMS_QUANTITY");
        assertEquals(100, customsService.getLedger("CI-CAP").reservedQuantity(), "失败不得占用余量");
        assertEquals(0, countBindings("B-CAP-3"), "失败不得写入绑定");
    }

    @Test
    void enterpriseMismatchRejectsWholeBinding() {
        // 通关单登记为其它企业/品种
        customsService.register(new CustomsRegisterRequest("reg-mis", "CI-MIS",
                "其它企业", IMPORT_PRODUCT, VALID_UNTIL, 100, T0));
        makeIssueable("d-mis", "B-MIS");

        assertBusiness(() -> customsService.bind(bindRequest("bind-mis", "CI-MIS", "B-MIS", 10, 1)),
                422, "BINDING_ENTERPRISE_MISMATCH");
        assertEquals(0, customsService.getLedger("CI-MIS").reservedQuantity(), "资料不符整笔拒绝，不预占");
        assertEquals(0, countBindings("B-MIS"));
    }

    @Test
    void eachSubbatchBindsAtMostOneDeclaration() {
        customsService.register(registerRequest("reg-one-1", "CI-ONE-1", 100));
        customsService.register(registerRequest("reg-one-2", "CI-ONE-2", 100));
        makeIssueable("d-one", "B-ONE");

        assertEquals(200, customsService.bind(bindRequest("bind-one-1", "CI-ONE-1", "B-ONE", 10, 1)).httpStatus());
        assertBusiness(() -> customsService.bind(bindRequest("bind-one-2", "CI-ONE-2", "B-ONE", 10, 1)),
                422, "SUBBATCH_ALREADY_BOUND");
        assertEquals(0, customsService.getLedger("CI-ONE-2").reservedQuantity(), "第二张单不得被预占");
    }

    @Test
    void importIssueAtomicallyConvertsReservationToWriteOff() {
        customsService.register(registerRequest("reg-iss", "CI-ISS", 100));
        makeIssueable("d-iss", "B-ISS");
        customsService.bind(bindRequest("bind-iss", "CI-ISS", "B-ISS", 30, 1));

        clock.setFixed(ISSUE_AT);
        ServiceResult issued = reviewService.issue("B-ISS", new IssueRequest("issue-1"));
        assertEquals(200, issued.httpStatus());
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-ISS").status());

        CustomsLedgerView ledger = customsService.getLedger("CI-ISS");
        assertEquals(0, ledger.reservedQuantity(), "签发后本子批预占清零");
        assertEquals(30, ledger.writtenOffQuantity(), "预占原子转为已核销");
        assertEquals(70, ledger.availableQuantity());
        var binding = ledger.bindings().stream().filter(b -> b.batchNo().equals("B-ISS")).findFirst().orElseThrow();
        assertEquals("WRITTEN_OFF", binding.status());
        assertNotNull(binding.writtenOffAt());
    }

    @Test
    void issueExactlyAtValidUntilIsRejectedAndNothingWrittenOff() {
        customsService.register(new CustomsRegisterRequest("reg-exact", "CI-EXACT",
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, ISSUE_AT, 100, T0));
        makeIssueable("d-exact", "B-EXACT");
        customsService.bind(bindRequest("bind-exact", "CI-EXACT", "B-EXACT", 30, 1));

        clock.setFixed(ISSUE_AT);
        ServiceResult rejected = reviewService.issue("B-EXACT", new IssueRequest("issue-exact"));
        assertEquals(422, rejected.httpStatus());
        assertEquals("CUSTOMS_EXPIRED", errorCode(rejected));

        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-EXACT").status(),
                "恰截止拒绝不得发证");
        CustomsLedgerView ledger = customsService.getLedger("CI-EXACT");
        assertEquals(30, ledger.reservedQuantity(), "拒绝不得扣减余量");
        assertEquals(0, ledger.writtenOffQuantity());
    }

    @Test
    void withdrawBeforeIssueReleasesReservationExactlyOnce() {
        customsService.register(registerRequest("reg-wd", "CI-WD", 100));
        makeIssueable("d-wd", "B-WD");
        customsService.bind(bindRequest("bind-wd", "CI-WD", "B-WD", 40, 1));

        ServiceResult withdrawn = customsService.withdraw(
                new SubbatchWithdrawRequest("wd-1", "B-WD", 1, T0.plusSeconds(60)));
        assertEquals(200, withdrawn.httpStatus());
        CustomsLedgerView ledger = (CustomsLedgerView) withdrawn.body();
        assertEquals(0, ledger.reservedQuantity());
        assertEquals(100, ledger.availableQuantity());
        var binding = ledger.bindings().stream().filter(b -> b.batchNo().equals("B-WD")).findFirst().orElseThrow();
        assertEquals("RELEASED", binding.status());
        assertEquals(2, binding.version(), "撤回释放成功后绑定版本加 1");
        assertNotNull(binding.releasedAt());

        // 再次撤回：只释放一次，第二次 422
        assertBusiness(() -> customsService.withdraw(
                new SubbatchWithdrawRequest("wd-2", "B-WD", 2, T0.plusSeconds(120))),
                422, "SUBBATCH_ALREADY_RELEASED");
    }

    @Test
    void revokeReleasesUnissuedAndKeepsIssuedEvidenceWithRiskEvent() {
        customsService.register(registerRequest("reg-rv", "CI-RV", 100));
        makeIssueable("d-rv-a", "B-RV-A");
        makeIssueable("d-rv-b", "B-RV-B");
        customsService.bind(bindRequest("bind-rv-a", "CI-RV", "B-RV-A", 30, 1));
        customsService.bind(bindRequest("bind-rv-b", "CI-RV", "B-RV-B", 20, 2));

        // A 先签发（已核销）；登记 v1 → 绑定 A v2 → 绑定 B v3 → A 签发货 v4
        clock.setFixed(ISSUE_AT);
        assertEquals(200, reviewService.issue("B-RV-A", new IssueRequest("issue-rv-a")).httpStatus());

        // 撤销通关单
        clock.setFixed(ISSUE_AT.plusSeconds(3600));
        ServiceResult revoked = customsService.revoke(
                new CustomsRevokeRequest("rv-1", "CI-RV", 4, ISSUE_AT.plusSeconds(3600)));
        assertEquals(200, revoked.httpStatus());
        CustomsLedgerView ledger = (CustomsLedgerView) revoked.body();
        assertEquals("REVOKED", ledger.status());
        assertEquals(0, ledger.reservedQuantity(), "未签发预占（B 的 20 支）被释放");
        assertEquals(30, ledger.writtenOffQuantity(), "已签发核销证据保持不变");

        var a = ledger.bindings().stream().filter(b -> b.batchNo().equals("B-RV-A")).findFirst().orElseThrow();
        var b = ledger.bindings().stream().filter(x -> x.batchNo().equals("B-RV-B")).findFirst().orElseThrow();
        assertEquals("WRITTEN_OFF", a.status(), "已签发证据不变");
        assertEquals("RELEASED", b.status(), "未签发预占释放");

        // 撤销后阻断 B 签发
        ServiceResult blocked = reviewService.issue("B-RV-B", new IssueRequest("issue-rv-b"));
        assertEquals(422, blocked.httpStatus());
        assertEquals("CUSTOMS_REVOKED_BLOCKS_ISSUE", errorCode(blocked));
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-RV-B").status());

        // A 已签发的风险事件已登记
        var events = reviewService.listEvents("B-RV-A");
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("CUSTOMS_REVOKED_AFTER_ISSUE")),
                "撤销时对已签发子批登记风险事件");
    }

    @Test
    void amendOnlyIncreasesTotalAndDoesNotChangeHistory() {
        customsService.register(registerRequest("reg-am", "CI-AM", 100));
        makeIssueable("d-am", "B-AM");
        customsService.bind(bindRequest("bind-am", "CI-AM", "B-AM", 30, 1));

        ServiceResult increased = customsService.amend(
                new CustomsAmendRequest("am-1", "CI-AM", 150, 2, T0.plusSeconds(30)));
        assertEquals(200, increased.httpStatus());
        CustomsLedgerView ledger = (CustomsLedgerView) increased.body();
        assertEquals(150, ledger.totalQuantity());
        assertEquals(30, ledger.reservedQuantity(), "历史预占不变");
        assertEquals(120, ledger.availableQuantity());

        // 不允许降到原数以下/相等
        assertBusiness(() -> customsService.amend(
                new CustomsAmendRequest("am-2", "CI-AM", 150, 3, T0.plusSeconds(60))),
                422, "AMEND_MUST_INCREASE");
    }

    @Test
    void notFoundReturns404() {
        assertBusiness(() -> customsService.revoke(new CustomsRevokeRequest("x", "NO-SUCH", 1, T0)),
                404, "CUSTOMS_NOT_FOUND");
        assertBusiness(() -> customsService.bind(bindRequest("x", "NO-SUCH", "B-NO", 10, 1)),
                404, "CUSTOMS_NOT_FOUND");
        customsService.register(registerRequest("reg-nb", "CI-NB", 100));
        assertBusiness(() -> customsService.bind(bindRequest("x", "CI-NB", "B-NO-BATCH", 10, 1)),
                404, "REVIEW_NOT_FOUND");
        assertBusiness(() -> customsService.withdraw(new SubbatchWithdrawRequest("x", "B-NO-BATCH", 1, T0)),
                404, "REVIEW_NOT_FOUND");
    }

    private int countBindings(String batchNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subbatch_binding s
                JOIN batch_review r ON r.id = s.review_id
                WHERE r.batch_no = ?
                """, Integer.class, batchNo);
        return count == null ? 0 : count;
    }
}
