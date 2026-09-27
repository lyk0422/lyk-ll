package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionUploadResult;
import com.example.starter.support.ApiException;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 采集包批量上传：有序入账、乱序水位线、整批拒绝、返工/隔离规则与幂等边界。
 * 测量时刻均落在绑定工具校验当天（2026-09-26，UTC）。
 */
class CollectionUploadServiceTest extends AbstractServiceTest {

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    /** 登记工具、派工双点卡（P1 标称 100、P2 标称 50）并创建采集会话。 */
    private void prepareSession(String sessionNo, String cardNo, String station) {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        operationService.dispatch(TestRequests.dispatch("d-" + cardNo, cardNo, station, "N"));
        Outcome created = collectionService.createSession(
                TestRequests.createSession("cs-" + sessionNo, sessionNo, cardNo, "TW-1"));
        assertThat(created.status()).isEqualTo(201);
    }

    private CollectionUploadResult upload(String requestId, String sessionNo, int version,
                                          com.example.starter.api.dto.CollectionUploadRequest.PacketSpec... packets) {
        Outcome outcome = collectionService.upload(
                TestRequests.upload(requestId, sessionNo, version, packets));
        assertThat(outcome.status()).isEqualTo(200);
        return (CollectionUploadResult) outcome.body();
    }

    @Test
    @DisplayName("顺序上传主流程：前缀入账推进水位线，力矩记录生成，全部合格后卡转 TIGHTENED，版本递增")
    void inOrderUploadMainFlow() {
        prepareSession("SES-U1", "CARD-U1", "S-U1");

        CollectionUploadResult first = upload("u-1", "SES-U1", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"));
        assertThat(first.appended()).isEqualTo(1);
        assertThat(first.consumed()).isEqualTo(1);
        assertThat(first.watermark()).isEqualTo(1);
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.cardStatus()).isEqualTo("DISPATCHED");
        assertThat(first.late()).isFalse();

        CollectionUploadResult second = upload("u-2", "SES-U1", 2,
                TestRequests.packet(2, "P2", "50.000", "2026-09-26T01:01:00Z"));
        assertThat(second.watermark()).isEqualTo(2);
        assertThat(second.version()).isEqualTo(3);
        assertThat(second.cardStatus()).isEqualTo("TIGHTENED");

        // 力矩记录 2 条，无返工；会话统计一致
        assertThat(operationService.trace("CARD-U1").torqueCurve()).hasSize(2);
        assertThat(operationService.trace("CARD-U1").reworkLedger()).isEmpty();
        CollectionSessionView view = collectionService.getSession("SES-U1");
        assertThat(view.watermark()).isEqualTo(2);
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.consumedCount()).isEqualTo(2);
        assertThat(view.bufferedCount()).isZero();
        // 批次台账 2 批
        assertThat(collectionService.listBatches("SES-U1")).hasSize(2);
        // 包证据含内容指纹
        assertThat(collectionService.listPackets("SES-U1"))
                .hasSize(2)
                .allSatisfy(p -> {
                    assertThat(p.status()).isEqualTo("CONSUMED");
                    assertThat(p.contentHash()).hasSize(64);
                });
    }

    @Test
    @DisplayName("乱序缓冲：先到 2 号包仅缓冲不消费，补齐 1 号后按前缀连续入账")
    void outOfOrderBufferThenConsume() {
        prepareSession("SES-U2", "CARD-U2", "S-U2");

        // 2 号先到：缓冲，水位线不动，不产生力矩记录
        CollectionUploadResult buffered = upload("u-o1", "SES-U2", 1,
                TestRequests.packet(2, "P2", "50.000", "2026-09-26T01:00:00Z"));
        assertThat(buffered.appended()).isEqualTo(1);
        assertThat(buffered.consumed()).isZero();
        assertThat(buffered.watermark()).isZero();
        assertThat(operationService.trace("CARD-U2").torqueCurve()).isEmpty();
        assertThat(collectionService.getSession("SES-U2").bufferedCount()).isEqualTo(1);

        // 补齐 1 号：1、2 连续入账
        CollectionUploadResult filled = upload("u-o2", "SES-U2", 2,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T00:59:00Z"));
        assertThat(filled.consumed()).isEqualTo(2);
        assertThat(filled.watermark()).isEqualTo(2);
        assertThat(filled.cardStatus()).isEqualTo("TIGHTENED");
        assertThat(operationService.trace("CARD-U2").torqueCurve()).hasSize(2);
        assertThat(collectionService.getSession("SES-U2").bufferedCount()).isZero();
    }

    @Test
    @DisplayName("超窗整批拒绝：水位线 0 时 21 号包 422，同批有效 1 号也不写入")
    void windowExceededRejectsWholeBatch() {
        prepareSession("SES-U3", "CARD-U3", "S-U3");

        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-w1", "SES-U3", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"),
                TestRequests.packet(21, "P2", "50.000", "2026-09-26T01:01:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("COLLECTION_WINDOW_EXCEEDED");
                });

        // 整批拒绝：无包、无力矩、水位线与版本不变
        assertThat(collectionService.listPackets("SES-U3")).isEmpty();
        assertThat(operationService.trace("CARD-U3").torqueCurve()).isEmpty();
        CollectionSessionView view = collectionService.getSession("SES-U3");
        assertThat(view.watermark()).isZero();
        assertThat(view.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("批内序号重复 400；未知点 404；均整批拒绝不写前半批")
    void duplicateSeqAndUnknownPointRejected() {
        prepareSession("SES-U4", "CARD-U4", "S-U4");

        // 批内重复序号 → 400
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-d1", "SES-U4", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"),
                TestRequests.packet(1, "P2", "50.000", "2026-09-26T01:01:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("DUPLICATE_SEQ_IN_BATCH");
                });

        // 未知点（不在绑定清单）→ 404，同批 1 号有效包也不写入
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-d2", "SES-U4", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"),
                TestRequests.packet(2, "P-UNKNOWN", "50.000", "2026-09-26T01:01:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getErrorCode()).isEqualTo("COLLECTION_POINT_UNKNOWN");
                });

        assertThat(collectionService.listPackets("SES-U4")).isEmpty();
        assertThat(collectionService.getSession("SES-U4").version()).isEqualTo(1);
    }

    @Test
    @DisplayName("同会话同序号：同内容重放不追加（异 requestId 也跳过），异内容 409")
    void sameSeqReplayAndConflict() {
        prepareSession("SES-U5", "CARD-U5", "S-U5");
        upload("u-r1", "SES-U5", 1, TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"));

        // 同内容重放（新 requestId）：不追加、不消费、版本不变
        CollectionUploadResult replay = upload("u-r2", "SES-U5", 2,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"));
        assertThat(replay.appended()).isZero();
        assertThat(replay.skippedDuplicates()).isEqualTo(1);
        assertThat(replay.version()).isEqualTo(2);
        assertThat(collectionService.listPackets("SES-U5")).hasSize(1);
        assertThat(operationService.trace("CARD-U5").torqueCurve()).hasSize(1);

        // 异内容同序号 → 409
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-r3", "SES-U5", 2,
                TestRequests.packet(1, "P1", "101.000", "2026-09-26T01:00:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("PACKET_CONTENT_CONFLICT");
                });
    }

    @Test
    @DisplayName("测量日非绑定校验版本当天 422；失败不占键，同 requestId 修正后重试成功")
    void calibrationCheckedByMeasurementInstant() {
        prepareSession("SES-U6", "CARD-U6", "S-U6");

        // 测量时刻为次日（2026-09-27），校验版本为 2026-09-26 → 422
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-c1", "SES-U6", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-27T00:30:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("TOOL_CALIBRATION_EXPIRED");
                });

        // 失败不占键：同 requestId 修正测量时刻后成功
        CollectionUploadResult retried = upload("u-c1", "SES-U6", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T23:30:00Z"));
        assertThat(retried.watermark()).isEqualTo(1);
    }

    @Test
    @DisplayName("版本冲突 409：expectedVersion 不等于当前版本拒绝且不变更")
    void versionConflictRejected() {
        prepareSession("SES-U7", "CARD-U7", "S-U7");
        upload("u-v1", "SES-U7", 1, TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"));

        // 过期版本 → 409
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-v2", "SES-U7", 1,
                TestRequests.packet(2, "P2", "50.000", "2026-09-26T01:01:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT");
                });
        assertThat(collectionService.listPackets("SES-U7")).hasSize(1);

        // 正确版本继续成功
        CollectionUploadResult ok = upload("u-v3", "SES-U7", 2,
                TestRequests.packet(2, "P2", "50.000", "2026-09-26T01:01:00Z"));
        assertThat(ok.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("超差沿用返工规则：第 1、2 次超差记返工，第 3 次超差整批隔离并停止消费")
    void outOfToleranceReworkAndQuarantine() {
        prepareSession("SES-U8", "CARD-U8", "S-U8");

        // 同一紧固点 P1 连续 3 次超差（标称 100，实测 200）
        CollectionUploadResult r1 = upload("u-q1", "SES-U8", 1,
                TestRequests.packet(1, "P1", "200.000", "2026-09-26T01:00:00Z"));
        assertThat(r1.cardStatus()).isEqualTo("DISPATCHED");
        CollectionUploadResult r2 = upload("u-q2", "SES-U8", 2,
                TestRequests.packet(2, "P1", "200.000", "2026-09-26T01:01:00Z"));
        assertThat(r2.cardStatus()).isEqualTo("DISPATCHED");
        CollectionUploadResult r3 = upload("u-q3", "SES-U8", 3,
                TestRequests.packet(3, "P1", "200.000", "2026-09-26T01:02:00Z"),
                TestRequests.packet(4, "P2", "50.000", "2026-09-26T01:03:00Z"));

        // 第 3 次超差触发整批隔离；4 号包已缓冲但不再消费
        assertThat(r3.cardStatus()).isEqualTo("QUARANTINED");
        assertThat(r3.watermark()).isEqualTo(3);
        assertThat(operationService.getCard("CARD-U8").status()).isEqualTo("QUARANTINED");
        assertThat(operationService.trace("CARD-U8").torqueCurve()).hasSize(3);
        assertThat(operationService.trace("CARD-U8").reworkLedger()).hasSize(2);
        CollectionSessionView view = collectionService.getSession("SES-U8");
        assertThat(view.bufferedCount()).isEqualTo(1);
        assertThat(view.consumedCount()).isEqualTo(3);

        // 隔离后上传冻结 → 422
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-q4", "SES-U8", 4,
                TestRequests.packet(5, "P2", "50.000", "2026-09-26T01:04:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("CARD_QUARANTINED");
                });
    }

    @Test
    @DisplayName("同 requestId 同参重放首次冻结结果；同键异参 409")
    void uploadIdempotencyReplayAndConflict() {
        prepareSession("SES-U9", "CARD-U9", "S-U9");
        upload("u-i1", "SES-U9", 1, TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z"));

        // 注意：首次上传后版本已变为 2，重放须用与首次完全相同的请求（含 expectedVersion=1）
        Outcome replay = collectionService.upload(TestRequests.upload(
                "u-i1", "SES-U9", 1, TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z")));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replay()).isTrue();
        assertThat(((CollectionUploadResult) replay.body()).version()).isEqualTo(2);

        // 同键异参（不同力矩）→ 409
        Outcome conflict = collectionService.upload(TestRequests.upload(
                "u-i1", "SES-U9", 1, TestRequests.packet(1, "P1", "101.000", "2026-09-26T01:00:00Z")));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((java.util.Map<?, ?>) conflict.body()).get("errorCode"))
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        // 状态未被重放或冲突改变
        assertThat(collectionService.listPackets("SES-U9")).hasSize(1);
        assertThat(operationService.trace("CARD-U9").torqueCurve()).hasSize(1);
    }

    @Test
    @DisplayName("批量大小边界：单批 20 包合法入账，21 包 400")
    void batchSizeBoundary() {
        prepareSession("SES-U10", "CARD-U10", "S-U10");

        // 20 包：P1/P2 交替，序号 1～20，全部在窗口内
        var packets = new com.example.starter.api.dto.CollectionUploadRequest.PacketSpec[20];
        for (int i = 1; i <= 20; i++) {
            String point = i % 2 == 1 ? "P1" : "P2";
            String torque = i % 2 == 1 ? "100.000" : "50.000";
            packets[i - 1] = TestRequests.packet(i, point, torque,
                    "2026-09-26T01:" + String.format("%02d", i - 1) + ":00Z");
        }
        CollectionUploadResult ok = upload("u-b1", "SES-U10", 1, packets);
        assertThat(ok.appended()).isEqualTo(20);
        assertThat(ok.consumed()).isEqualTo(20);
        assertThat(ok.watermark()).isEqualTo(20);
        assertThat(ok.cardStatus()).isEqualTo("TIGHTENED");

        // 21 包 → 400
        var tooMany = new com.example.starter.api.dto.CollectionUploadRequest.PacketSpec[21];
        for (int i = 1; i <= 21; i++) {
            tooMany[i - 1] = TestRequests.packet(20 + i, "P1", "100.000",
                    "2026-09-26T02:" + String.format("%02d", i - 1) + ":00Z");
        }
        assertThatThrownBy(() -> collectionService.upload(
                TestRequests.upload("u-b2", "SES-U10", 2, tooMany)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("BATCH_SIZE_INVALID");
                });
    }
}
