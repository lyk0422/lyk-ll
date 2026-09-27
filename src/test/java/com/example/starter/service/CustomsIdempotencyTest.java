package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 新增写操作幂等测试：同键同参重放首次冻结结果、异参 409、失败不占键。
 */
class CustomsIdempotencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-07T02:00:00Z");
    private static final LocalDate VALID_UNTIL = LocalDate.of(2026, 10, 31);
    private static final Instant OCCURRED = Instant.parse("2026-09-07T02:00:00Z");

    @Autowired
    private CustomsService customsService;

    @BeforeEach
    void fixClock() {
        clock.setFixed(T0);
    }

    @Test
    void registerSameKeySameParametersReplaysFrozenResult() {
        CustomsRegisterRequest request = new CustomsRegisterRequest(
                "c-idem-1", "CD-I-1", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100);
        ServiceResult first = customsService.register(request);
        ServiceResult replay = customsService.register(request);
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus(), "同键同参必须重放首次冻结结果而不是重复登记");
        assertEquals(1, countDeclarations("CD-I-1"), "重放不得产生第二条通关单");
    }

    @Test
    void registerSameKeyDifferentParametersConflicts409() {
        customsService.register(new CustomsRegisterRequest(
                "c-idem-2", "CD-I-2", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        ServiceResult conflict = customsService.register(new CustomsRegisterRequest(
                "c-idem-2", "CD-I-2", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 200));
        assertEquals(409, conflict.httpStatus(), "同键异参必须 409");
        CustomsDeclarationView view = customsService.getDeclaration("CD-I-2");
        assertEquals(100, view.totalQuantity(), "异参请求不得改变首次登记结果");
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        customsService.register(new CustomsRegisterRequest(
                "c-idem-3-r", "CD-I-3", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        declareBatch("c-idem-3-d", "B-CI-1");

        // 第一次绑定超量失败（422）：失败不占键。
        ServiceResult failed = customsService.bind(new CustomsBindRequest(
                "c-idem-3-b", "CD-I-3", "B-CI-1", 101, 1, OCCURRED));
        assertEquals(422, failed.httpStatus());
        assertEquals(0, countIdempotency("c-idem-3-b"), "失败请求不得冻结幂等键");

        // 同一 requestId 修正参数后可重新执行成功。
        ServiceResult retried = customsService.bind(new CustomsBindRequest(
                "c-idem-3-b", "CD-I-3", "B-CI-1", 50, 1, OCCURRED));
        assertEquals(200, retried.httpStatus(), "失败不占键，同键修正参数后必须可重新执行");
        assertEquals(50, customsService.getDeclaration("CD-I-3").reservedQuantity());
    }

    @Test
    void bindFingerprintCoversVersionAndOccurredAt() {
        customsService.register(new CustomsRegisterRequest(
                "c-idem-4-r", "CD-I-4", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        declareBatch("c-idem-4-d", "B-CI-2");
        customsService.bind(new CustomsBindRequest("c-idem-4-b", "CD-I-4", "B-CI-2", 10, 1, OCCURRED));

        // 同键但 occurredAt 不同：异参 409（精确时刻参与指纹）。
        ServiceResult differentTime = customsService.bind(new CustomsBindRequest(
                "c-idem-4-b", "CD-I-4", "B-CI-2", 10, 1, Instant.parse("2026-09-08T02:00:00Z")));
        assertEquals(409, differentTime.httpStatus());
        // 同键但 expectedVersion 不同：异参 409（版本参与指纹）。
        ServiceResult differentVersion = customsService.bind(new CustomsBindRequest(
                "c-idem-4-b", "CD-I-4", "B-CI-2", 10, 2, OCCURRED));
        assertEquals(409, differentVersion.httpStatus());
    }

    @Test
    void withdrawSameKeyReplaysAndDoesNotReleaseTwice() {
        customsService.register(new CustomsRegisterRequest(
                "c-idem-5-r", "CD-I-5", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        declareBatch("c-idem-5-d", "B-CI-3");
        customsService.bind(new CustomsBindRequest("c-idem-5-b", "CD-I-5", "B-CI-3", 40, 1, OCCURRED));

        CustomsWithdrawRequest withdraw = new CustomsWithdrawRequest("c-idem-5-w", "B-CI-3", 1, OCCURRED);
        ServiceResult first = customsService.withdraw(withdraw);
        ServiceResult replay = customsService.withdraw(withdraw);
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus(), "同键同参重放首次撤回结果");
        assertEquals(100, customsService.getDeclaration("CD-I-5").availableQuantity(),
                "预占只释放一次，重放不得重复释放");
        assertEquals(ReviewStatus.WITHDRAWN.name(), reviewService.getReview("B-CI-3").status());
    }

    @Test
    void differentOperationsWithSameKeyAreNamespacedByFingerprint() {
        // 同一 requestId 用于不同操作类型：指纹含操作命名空间，应判异参 409 而非误重放。
        customsService.register(new CustomsRegisterRequest(
                "c-idem-6", "CD-I-6", "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, 100));
        declareBatch("c-idem-6-d", "B-CI-4");
        ServiceResult bindWithSameKey = customsService.bind(new CustomsBindRequest(
                "c-idem-6", "CD-I-6", "B-CI-4", 10, 1, OCCURRED));
        assertEquals(409, bindWithSameKey.httpStatus(),
                "requestId 全局唯一：跨操作复用同键判异参冲突");
        assertNotEquals(200, bindWithSameKey.httpStatus());
    }

    private void declareBatch(String requestId, String batchNo) {
        assertEquals(200, reviewService.declare(declareRequest(requestId, batchNo,
                allDocuments(), boundaryPassingInspections())).httpStatus());
    }

    private int countDeclarations(String declarationNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customs_declaration WHERE declaration_no = ?",
                Integer.class, declarationNo);
        return count == null ? 0 : count;
    }

    private int countIdempotency(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }
}
