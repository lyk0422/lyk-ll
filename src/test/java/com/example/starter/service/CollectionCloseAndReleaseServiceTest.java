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
 * 采集会话关闭、归档门禁与归档后迟到包：
 * 关会话须无缺号且缓冲全部消费；工序归档前全部采集会话关闭；归档后包只记迟到。
 */
class CollectionCloseAndReleaseServiceTest extends AbstractServiceTest {

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    /** 登记工具、派工单点卡（P1 标称 100）并创建采集会话。 */
    private void prepareSession(String sessionNo, String cardNo, String station) {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        operationService.dispatch(
                TestRequests.singlePoint("d-" + cardNo, cardNo, station, "N", "P1", "100.000"));
        collectionService.createSession(TestRequests.createSession("cs-" + sessionNo, sessionNo, cardNo, "TW-1"));
    }

    private CollectionUploadResult upload(String requestId, String sessionNo, int version, int seq,
                                          String torque, String measuredAt) {
        Outcome outcome = collectionService.upload(TestRequests.upload(
                requestId, sessionNo, version, TestRequests.packet(seq, "P1", torque, measuredAt)));
        assertThat(outcome.status()).isEqualTo(200);
        return (CollectionUploadResult) outcome.body();
    }

    @Test
    @DisplayName("关会话主流程：缓冲全部消费后关闭成功，版本加 1；重复关闭 422；同键重放冻结结果")
    void closeSessionMainFlow() {
        prepareSession("SES-K1", "CARD-K1", "S-K1");
        upload("u-k1", "SES-K1", 1, 1, "100.000", "2026-09-26T01:00:00Z");

        Outcome closed = collectionService.closeSession(TestRequests.closeSession("cl-k1", "SES-K1", 2));
        assertThat(closed.status()).isEqualTo(200);
        CollectionSessionView view = (CollectionSessionView) closed.body();
        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.closedAt()).isNotNull();

        // 同 requestId 同参：重放首次冻结结果
        Outcome replay = collectionService.closeSession(TestRequests.closeSession("cl-k1", "SES-K1", 2));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replay()).isTrue();

        // 异 requestId 重复关闭 → 422
        assertThatThrownBy(() -> collectionService.closeSession(
                TestRequests.closeSession("cl-k1-again", "SES-K1", 3)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("SESSION_CLOSED");
                });
    }

    @Test
    @DisplayName("有缺号（缓冲未消费）拒绝关闭 422；版本冲突 409")
    void closeSessionFailures() {
        prepareSession("SES-K2", "CARD-K2", "S-K2");
        // 只上传 2 号包：缓冲未消费（缺 1 号）
        upload("u-k2", "SES-K2", 1, 2, "100.000", "2026-09-26T01:00:00Z");
        assertThat(collectionService.getSession("SES-K2").bufferedCount()).isEqualTo(1);

        assertThatThrownBy(() -> collectionService.closeSession(
                TestRequests.closeSession("cl-k2", "SES-K2", 2)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("COLLECTION_BUFFER_PENDING");
                });

        // 版本冲突 → 409
        assertThatThrownBy(() -> collectionService.closeSession(
                TestRequests.closeSession("cl-k2b", "SES-K2", 99)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT");
                });

        // 补齐 1 号后缓冲消费，可关闭
        upload("u-k2-fill", "SES-K2", 2, 1, "100.000", "2026-09-26T00:59:00Z");
        Outcome closed = collectionService.closeSession(TestRequests.closeSession("cl-k2c", "SES-K2", 3));
        assertThat(closed.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("已关闭会话（卡未归档）拒绝上传 422")
    void uploadToClosedSessionRejected() {
        prepareSession("SES-K3", "CARD-K3", "S-K3");
        upload("u-k3", "SES-K3", 1, 1, "100.000", "2026-09-26T01:00:00Z");
        collectionService.closeSession(TestRequests.closeSession("cl-k3", "SES-K3", 2));

        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-k3-late", "SES-K3", 3,
                TestRequests.packet(2, "P1", "100.000", "2026-09-26T01:01:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("SESSION_CLOSED");
                });
    }

    @Test
    @DisplayName("归档门禁：存在未关闭采集会话拒绝放行 422；关闭后放行成功")
    void releaseBlockedByOpenSession() {
        prepareSession("SES-K4", "CARD-K4", "S-K4");
        upload("u-k4", "SES-K4", 1, 1, "100.000", "2026-09-26T01:00:00Z");
        // 采集入账已使卡 TIGHTENED，完成自检互检
        assertThat(operationService.getCard("CARD-K4").status()).isEqualTo("TIGHTENED");
        operationService.inspect(TestRequests.inspection("i-k4", "CARD-K4", "p1", "p2"));

        // 会话未关闭 → 放行拒绝
        Outcome blocked = operationService.release(TestRequests.release("r-k4", "CARD-K4", "qa"));
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) blocked.body()).get("errorCode"))
                .isEqualTo("COLLECTION_SESSION_OPEN");
        assertThat(operationService.getCard("CARD-K4").status()).isEqualTo("INSPECTED");

        // 关闭会话后放行成功（新 requestId）
        collectionService.closeSession(TestRequests.closeSession("cl-k4", "SES-K4", 2));
        Outcome released = operationService.release(TestRequests.release("r-k4-2", "CARD-K4", "qa"));
        assertThat(released.status()).isEqualTo(200);
        assertThat(operationService.getCard("CARD-K4").status()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("归档后包只记迟到：不复活卡片、不进力矩链、水位线不动；同内容重放不追加，异内容 409")
    void latePacketsAfterArchive() {
        prepareSession("SES-K5", "CARD-K5", "S-K5");
        upload("u-k5", "SES-K5", 1, 1, "100.000", "2026-09-26T01:00:00Z");
        collectionService.closeSession(TestRequests.closeSession("cl-k5", "SES-K5", 2));
        operationService.inspect(TestRequests.inspection("i-k5", "CARD-K5", "p1", "p2"));
        operationService.release(TestRequests.release("r-k5", "CARD-K5", "qa"));
        assertThat(operationService.getCard("CARD-K5").status()).isEqualTo("ARCHIVED");

        // 归档后上传 2 号包：只记迟到
        CollectionUploadResult late = upload("u-k5-late", "SES-K5", 3, 2, "105.000",
                "2026-09-26T01:05:00Z");
        assertThat(late.late()).isTrue();
        assertThat(late.cardStatus()).isEqualTo("ARCHIVED");
        assertThat(late.watermark()).isEqualTo(1);
        assertThat(late.consumed()).isZero();

        // 不复活卡片、不进力矩链
        assertThat(operationService.getCard("CARD-K5").status()).isEqualTo("ARCHIVED");
        assertThat(operationService.trace("CARD-K5").torqueCurve()).hasSize(1);
        // 包证据：2 号包为 LATE
        assertThat(collectionService.listPackets("SES-K5"))
                .filteredOn(p -> p.seq() == 2)
                .singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo("LATE"));
        assertThat(collectionService.getSession("SES-K5").lateCount()).isEqualTo(1);

        // 同内容重放（新 requestId）：不追加
        CollectionUploadResult replay = upload("u-k5-late-2", "SES-K5", 4, 2, "105.000",
                "2026-09-26T01:05:00Z");
        assertThat(replay.skippedDuplicates()).isEqualTo(1);
        assertThat(collectionService.listPackets("SES-K5")).hasSize(2);

        // 异内容同序号 → 409
        assertThatThrownBy(() -> collectionService.upload(TestRequests.upload(
                "u-k5-late-3", "SES-K5", 4,
                TestRequests.packet(2, "P1", "106.000", "2026-09-26T01:05:00Z"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("PACKET_CONTENT_CONFLICT");
                });
    }

    @Test
    @DisplayName("多会话：全部关闭才可归档；查询视图水位线/版本/统计一致")
    void multipleSessionsMustAllClose() {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        operationService.dispatch(TestRequests.dispatch("d-CARD-K6", "CARD-K6", "S-K6", "N"));
        collectionService.createSession(TestRequests.createSession("cs-a", "SES-K6A", "CARD-K6", "TW-1"));
        collectionService.createSession(TestRequests.createSession("cs-b", "SES-K6B", "CARD-K6", "TW-1"));

        // 会话 A 入账 P1，会话 B 入账 P2
        collectionService.upload(TestRequests.upload("u-a", "SES-K6A", 1,
                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z")));
        collectionService.upload(TestRequests.upload("u-b", "SES-K6B", 1,
                TestRequests.packet(1, "P2", "50.000", "2026-09-26T01:01:00Z")));
        assertThat(operationService.getCard("CARD-K6").status()).isEqualTo("TIGHTENED");
        operationService.inspect(TestRequests.inspection("i-k6", "CARD-K6", "p1", "p2"));

        // 只关闭 A → 仍拒绝
        collectionService.closeSession(TestRequests.closeSession("cl-a", "SES-K6A", 2));
        Outcome blocked = operationService.release(TestRequests.release("r-k6", "CARD-K6", "qa"));
        assertThat(blocked.status()).isEqualTo(422);

        // 关闭 B → 放行成功
        collectionService.closeSession(TestRequests.closeSession("cl-b", "SES-K6B", 2));
        Outcome released = operationService.release(TestRequests.release("r-k6-2", "CARD-K6", "qa"));
        assertThat(released.status()).isEqualTo(200);

        CollectionSessionView viewA = collectionService.getSession("SES-K6A");
        assertThat(viewA.status()).isEqualTo("CLOSED");
        assertThat(viewA.watermark()).isEqualTo(1);
        assertThat(viewA.consumedCount()).isEqualTo(1);
        assertThat(collectionService.listBatches("SES-K6B")).hasSize(1);
    }
}
