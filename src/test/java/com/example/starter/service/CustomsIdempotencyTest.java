package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.BindSubBatchRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.RegisterDeclarationRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通关写操作幂等测试：同键同参重放首次冻结结果；同键异参 409；
 * 失败（4xx）不占键，同键可重新发起并成功。
 */
class CustomsIdempotencyTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");

    private RegisterDeclarationRequest registerRequest(String requestId, String declarationNo, int total) {
        return new RegisterDeclarationRequest(requestId, declarationNo,
                "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, total);
    }

    @Test
    void sameKeySameParametersReplaysFrozenFirstResult() {
        clock.setFixed(NOW);
        RegisterDeclarationRequest request = registerRequest("idem-c1", "CUS-I1", 100);
        ServiceResult first = customsService.register(request);
        ServiceResult replay = customsService.register(request);
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus());
        CustomsDeclarationView firstView = (CustomsDeclarationView) first.body();
        CustomsDeclarationView replayView = (CustomsDeclarationView) jsonSupport
                .convertValue(replay.body(), CustomsDeclarationView.class);
        assertEquals(firstView.declarationNo(), replayView.declarationNo());
        assertEquals(firstView.version(), replayView.version(), "重放必须返回首次冻结结果");
        assertEquals(1, countDeclarations("CUS-I1"), "重放不得产生第二张通关单");
    }

    @Test
    void sameKeyDifferentParametersConflictsWithoutStateChange() {
        clock.setFixed(NOW);
        customsService.register(registerRequest("idem-c2", "CUS-I2", 100));

        // 同 requestId，变更可核销总数
        ServiceResult conflict = customsService.register(registerRequest("idem-c2", "CUS-I2", 200));
        assertEquals(409, conflict.httpStatus());
        assertEquals(100, customsService.getDeclaration("CUS-I2").totalQuantity(),
                "同键异参不得改变首次登记结果");

        // 同 requestId，文本参数含分隔符的细微差异也必须识别为异参
        ServiceResult tampered = customsService.register(new RegisterDeclarationRequest(
                "idem-c2", "CUS-I2", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        assertEquals(200, tampered.httpStatus(), "完整参数一致必须重放而非冲突");
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndCanBeRetried() {
        clock.setFixed(NOW);
        customsService.register(registerRequest("idem-c3-reg", "CUS-I3", 100));
        reviewService.declare(declareRequest("idem-c3-d", "B-CUS-I3", allDocuments(),
                boundaryPassingInspections()));

        // 第一次绑定：数量超过总数，业务拒绝 422
        ServiceResult failed = customsService.bind("CUS-I3",
                new BindSubBatchRequest("idem-c3-bind", "B-CUS-I3", 150, 1));
        assertEquals(422, failed.httpStatus());

        // 失败不占键：同一 requestId 携带修正后的参数可成功
        ServiceResult retried = customsService.bind("CUS-I3",
                new BindSubBatchRequest("idem-c3-bind", "B-CUS-I3", 60, 1));
        assertEquals(200, retried.httpStatus(), "失败不占键，同键修正参数后必须可成功");
        assertEquals(60, customsService.getDeclaration("CUS-I3").reservedQuantity());

        // 成功占键后：同键同参重放，同键异参 409
        ServiceResult replay = customsService.bind("CUS-I3",
                new BindSubBatchRequest("idem-c3-bind", "B-CUS-I3", 60, 1));
        assertEquals(200, replay.httpStatus());
        ServiceResult conflict = customsService.bind("CUS-I3",
                new BindSubBatchRequest("idem-c3-bind", "B-CUS-I3", 61, 1));
        assertEquals(409, conflict.httpStatus());
        assertEquals(60, customsService.getDeclaration("CUS-I3").reservedQuantity(),
                "异参冲突不得改变台账");
        assertEquals(1, countBindings("B-CUS-I3"), "重放与冲突不得产生第二条绑定");
    }

    @Test
    void notFoundFailureDoesNotOccupyKey() {
        clock.setFixed(NOW);
        // 对象不存在 404：不占键
        ServiceResult missing = customsService.revoke("CUS-I4",
                new com.example.starter.dto.RevokeDeclarationRequest("idem-c4", 1));
        assertEquals(404, missing.httpStatus());

        customsService.register(registerRequest("idem-c4-reg", "CUS-I4", 50));
        ServiceResult revoked = customsService.revoke("CUS-I4",
                new com.example.starter.dto.RevokeDeclarationRequest("idem-c4", 1));
        assertEquals(200, revoked.httpStatus(), "404 不占键，登记后同键撤销必须可成功");
        assertNotNull(customsService.getDeclaration("CUS-I4").revokedAt());
    }

    private int countDeclarations(String declarationNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customs_declaration WHERE declaration_no = ?",
                Integer.class, declarationNo);
        return count == null ? 0 : count;
    }

    private int countBindings(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sub_batch_binding WHERE batch_no = ?",
                Integer.class, batchNo);
        return count == null ? 0 : count;
    }
}
