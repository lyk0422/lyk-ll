package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 进口通关写操作 requestId 幂等 H2 测试：
 * 同键同参重放首次冻结结果；同键异参（含版本/时刻）409；业务失败不占键，可换参重试成功。
 */
class CustomsIdempotencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-10-01T00:00:00Z");

    private CustomsRegisterRequest register(String requestId, String no, int total, Instant at) {
        return new CustomsRegisterRequest(requestId, no,
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, total, at);
    }

    @Test
    void sameKeySameParametersReplaysFirstRegisterResult() {
        ServiceResult first = customsService.register(register("idem-reg", "CI-IR", 100, T0));
        ServiceResult replay = customsService.register(register("idem-reg", "CI-IR", 100, T0));
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus());
        CustomsLedgerView f = (CustomsLedgerView) first.body();
        CustomsLedgerView r = jsonSupport.convertValue(replay.body(), CustomsLedgerView.class);
        assertEquals(f.version(), r.version());
        assertEquals(1, countCustoms("CI-IR"), "重放不得重复登记");
    }

    @Test
    void sameKeyDifferentParametersConflicts() {
        customsService.register(register("idem-bind-reg", "CI-IB", 100, T0));
        clock.setFixed(T0);
        prepareIssueableImportBatch("idem-bind-d", "B-IB");
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity("B-IB");

        CustomsBindRequest bind = new CustomsBindRequest("idem-bind", "CI-IB", "B-IB", 10, 1, T0);
        assertEquals(200, customsService.bind(bind).httpStatus());
        // 同键改支数：409，且不改变首次预占
        CustomsBindRequest tampered = new CustomsBindRequest("idem-bind", "CI-IB", "B-IB", 20, 1, T0);
        ServiceResult conflict = customsService.bind(tampered);
        assertEquals(409, conflict.httpStatus());
        assertEquals(10, customsService.getLedger("CI-IB").reservedQuantity(),
                "同键异参必须重放/冲突，不改变首次预占");
        assertEquals(1, countBindings("B-IB"));
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndCanBeRetried() {
        // 首次用 idem-fail 绑定一个不存在的通关单：404 回滚，不占键
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> customsService.bind(new CustomsBindRequest("idem-fail", "CI-GONE",
                        "B-X", 10, 1, T0)));
        assertEquals(404, ex.getHttpStatus());
        assertEquals(0, countIdempotency("idem-fail"), "失败回滚不得留下幂等占位");

        // 同一 requestId 换为合法参数后可成功（证明键未被占用）
        customsService.register(register("idem-fail-reg", "CI-OK", 100, T0));
        clock.setFixed(T0);
        prepareIssueableImportBatch("idem-fail-d", "B-OK");
        ServiceResult ok = customsService.bind(new CustomsBindRequest("idem-fail", "CI-OK", "B-OK", 10, 1, T0));
        assertEquals(200, ok.httpStatus());
        assertEquals(10, customsService.getLedger("CI-OK").reservedQuantity());
    }

    @Test
    void replayAfterIssueReturnsFrozenLedgerAndRemainsIdempotent() {
        customsService.register(register("idem-iss-reg", "CI-IIS", 100, T0));
        clock.setFixed(T0);
        prepareIssueableImportBatch("idem-iss-d", "B-IIS");
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity("B-IIS");
        customsService.bind(new CustomsBindRequest("idem-iss-bind", "CI-IIS", "B-IIS", 25, 1, T0));

        ServiceResult issue = reviewService.issue("B-IIS", new IssueRequest("idem-issue"));
        ServiceResult replay = reviewService.issue("B-IIS", new IssueRequest("idem-issue"));
        assertEquals(200, issue.httpStatus());
        assertEquals(200, replay.httpStatus());
        assertEquals(25, customsService.getLedger("CI-IIS").writtenOffQuantity(), "重放签发不得重复核销");
        assertEquals(0, customsService.getLedger("CI-IIS").reservedQuantity());
    }

    @Test
    void revokeVersionMismatchConflictsAndKeyStartsAtCurrentVersion() {
        customsService.register(register("idem-rv-reg", "CI-IRV", 100, T0));
        clock.setFixed(T0);
        prepareIssueableImportBatch("idem-rv-d", "B-IRV");
        customsService.bind(new CustomsBindRequest("idem-rv-bind", "CI-IRV", "B-IRV", 10, 1, T0));
        // 绑定后版本为 2，仍提交 expectedVersion=1：409 版本冲突
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> customsService.revoke(new CustomsRevokeRequest("idem-rv", "CI-IRV", 1, T0)));
        assertEquals(409, ex.getHttpStatus());
        assertEquals("VERSION_CONFLICT", ex.getError());
        assertEquals("ACTIVE", customsService.getLedger("CI-IRV").status(), "版本冲突不得撤销");
        // 失败不占键：同键以当前版本 2 重试成功
        ServiceResult ok = customsService.revoke(new CustomsRevokeRequest("idem-rv", "CI-IRV", 2, T0));
        assertEquals(200, ok.httpStatus());
        assertEquals("REVOKED", jsonSupport.convertValue(ok.body(), CustomsLedgerView.class).status());
    }

    private int countCustoms(String customsNo) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customs_declaration WHERE customs_no = ?", Integer.class, customsNo);
        return c == null ? 0 : c;
    }

    private int countBindings(String batchNo) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subbatch_binding s
                JOIN batch_review r ON r.id = s.review_id WHERE r.batch_no = ?
                """, Integer.class, batchNo);
        return c == null ? 0 : c;
    }

    private int countIdempotency(String requestId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Integer.class, requestId);
        return c == null ? 0 : c;
    }
}
