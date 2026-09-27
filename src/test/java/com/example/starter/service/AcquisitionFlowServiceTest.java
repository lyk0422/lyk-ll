package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.AcquisitionBatchResult;
import com.example.starter.api.dto.AcquisitionSessionView;
import com.example.starter.api.dto.AcquisitionWatermarkView;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 本地力矩采集连续水位线测试：乱序缓冲、前缀入账、整批拒绝、
 * 关会话与归档门禁、迟到包与工具日校验版本。
 */
class AcquisitionFlowServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService operationService;

    @Autowired
    private AcquisitionService acquisitionService;

    /** 派工双紧固点 N 卡并登记当日有效工具，创建绑定 P1/P2 的采集会话。 */
    private void prepareSession(String cardNo, String station, String sessionNo) {
        operationService.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        operationService.dispatch(TestRequests.dispatch("d-" + cardNo, cardNo, station, "N"));
        Outcome created = acquisitionService.createSession(
                TestRequests.acqSession("s-" + sessionNo, sessionNo, cardNo, "T1"));
        assertThat(created.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("序号 2 先到 1 未到：缓冲 2 但不入账，水位线保持 0，不参与见证放行")
    void outOfOrderBufferedNotCommitted() {
        prepareSession("CARD-A1", "S-A1", "SES-A1");

        // 序号 2 先到：缓冲，水位线不推进
        Outcome first = acquisitionService.uploadBatch("SES-A1", TestRequests.batch(
                "b-a1-1", 1L, TestRequests.packet(2, "P1", "2026-09-26T08:00:02Z", "100.000")));
        assertThat(first.status()).isEqualTo(200);
        AcquisitionBatchResult firstResult = (AcquisitionBatchResult) first.body();
        assertThat(firstResult.committed()).isZero();
        assertThat(firstResult.watermark()).isZero();

        AcquisitionWatermarkView watermark = acquisitionService.getWatermark("SES-A1");
        assertThat(watermark.watermark()).isZero();
        assertThat(watermark.bufferedSeqs()).containsExactly(2);
        // 缓冲包不产生力矩记录，不能算进见证放行：工序仍未完成力矩紧固
        assertThat(operationService.trace("CARD-A1").torqueCurve()).isEmpty();
        assertThat(operationService.getCard("CARD-A1").status()).isEqualTo("DISPATCHED");

        // 序号 1 到达：前缀完整，1、2 连续入账，水位线推进到 2
        Outcome second = acquisitionService.uploadBatch("SES-A1", TestRequests.batch(
                "b-a1-2", 2L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));
        assertThat(second.status()).isEqualTo(200);
        AcquisitionBatchResult secondResult = (AcquisitionBatchResult) second.body();
        assertThat(secondResult.committed()).isEqualTo(2);
        assertThat(secondResult.watermark()).isEqualTo(2);
        assertThat(acquisitionService.getWatermark("SES-A1").bufferedSeqs()).isEmpty();
        assertThat(operationService.trace("CARD-A1").torqueCurve()).hasSize(2);
    }

    @Test
    @DisplayName("缺号包不参与自检互检与放行：缺口未补时工序停在 DISPATCHED")
    void gapPacketsDoNotRelease() {
        // 三紧固点卡：保证缺口补齐后连续入账期间工序始终处于 DISPATCHED
        operationService.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        operationService.dispatch(new com.example.starter.api.dto.DispatchRequest(
                "d-CARD-A2", "CARD-A2", "S-A2", "SN-CARD-A2", "N", "op-A",
                java.util.List.of(
                        new com.example.starter.api.dto.DispatchRequest.FastenerSpec(
                                "P1", new java.math.BigDecimal("100.000"), null),
                        new com.example.starter.api.dto.DispatchRequest.FastenerSpec(
                                "P2", new java.math.BigDecimal("50.000"), null),
                        new com.example.starter.api.dto.DispatchRequest.FastenerSpec(
                                "P3", new java.math.BigDecimal("50.000"), null))));
        assertThat(acquisitionService.createSession(new com.example.starter.api.dto.AcquisitionSessionCreateRequest(
                "s-SES-A2", "SES-A2", "CARD-A2", "T1", java.util.List.of("P1", "P2", "P3"))).status())
                .isEqualTo(201);

        // 只上传 1、3：1 入账，3 缓冲（缺 2）
        acquisitionService.uploadBatch("SES-A2", TestRequests.batch("b-a2-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(3, "P3", "2026-09-26T08:00:03Z", "50.000")));
        assertThat(acquisitionService.getWatermark("SES-A2").watermark()).isEqualTo(1);
        assertThat(acquisitionService.getWatermark("SES-A2").bufferedSeqs()).containsExactly(3);
        // P3 的缓冲包不算：工序未完成力矩紧固，自检互检拒绝
        assertThat(operationService.getCard("CARD-A2").status()).isEqualTo("DISPATCHED");
        Outcome inspect = operationService.inspect(
                TestRequests.inspection("i-a2", "CARD-A2", "a", "b"));
        assertThat(inspect.status()).isEqualTo(422);

        // 补上 2：2、3 连续入账，全部合格后进入 TIGHTENED
        Outcome fill = acquisitionService.uploadBatch("SES-A2", TestRequests.batch(
                "b-a2-2", 2L, TestRequests.packet(2, "P2", "2026-09-26T08:00:02Z", "50.000")));
        assertThat(((AcquisitionBatchResult) fill.body()).committed()).isEqualTo(2);
        assertThat(acquisitionService.getWatermark("SES-A2").watermark()).isEqualTo(3);
        assertThat(operationService.getCard("CARD-A2").status()).isEqualTo("TIGHTENED");
    }

    @Test
    @DisplayName("未知点/超窗/批内序号重复：任一错误整批拒绝，有效前半批也不写入")
    void batchRejectedAtomically() {
        prepareSession("CARD-A3", "S-A3", "SES-A3");

        // 未知点：第 1 包有效也不写入
        Outcome unknownPoint = acquisitionService.uploadBatch("SES-A3", TestRequests.batch(
                "b-a3-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(2, "P9", "2026-09-26T08:00:02Z", "100.000")));
        assertThat(unknownPoint.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) unknownPoint.body()).get("errorCode")).isEqualTo("UNKNOWN_POINT");
        assertThat(acquisitionService.listPackets("SES-A3")).isEmpty();
        assertThat(acquisitionService.getWatermark("SES-A3").watermark()).isZero();

        // 超窗：水位线 0，序号 21 超出第 1~20 号窗口
        Outcome outOfWindow = acquisitionService.uploadBatch("SES-A3", TestRequests.batch(
                "b-a3-2", 1L, TestRequests.packet(21, "P1", "2026-09-26T08:00:21Z", "100.000")));
        assertThat(outOfWindow.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outOfWindow.body()).get("errorCode")).isEqualTo("OUT_OF_WINDOW");
        assertThat(acquisitionService.listPackets("SES-A3")).isEmpty();

        // 批内序号重复
        Outcome dupSeq = acquisitionService.uploadBatch("SES-A3", TestRequests.batch(
                "b-a3-3", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(1, "P2", "2026-09-26T08:00:01Z", "50.000")));
        assertThat(dupSeq.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) dupSeq.body()).get("errorCode")).isEqualTo("BATCH_DUPLICATE_SEQ");
        assertThat(acquisitionService.listPackets("SES-A3")).isEmpty();
        // 失败不占键：修正参数后同键可成功
        Outcome fixed = acquisitionService.uploadBatch("SES-A3", TestRequests.batch(
                "b-a3-3", 1L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));
        assertThat(fixed.status()).isEqualTo(200);
        assertThat(acquisitionService.getWatermark("SES-A3").watermark()).isEqualTo(1);
    }

    @Test
    @DisplayName("同会话同序号：同内容重放不追加，异内容 409")
    void sameSeqReplayAndConflict() {
        prepareSession("CARD-A4", "S-A4", "SES-A4");
        acquisitionService.uploadBatch("SES-A4", TestRequests.batch(
                "b-a4-1", 1L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));

        // 同内容重放（新 requestId）：不追加记录
        Outcome replay = acquisitionService.uploadBatch("SES-A4", TestRequests.batch(
                "b-a4-2", 2L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(((AcquisitionBatchResult) replay.body()).replayed()).isEqualTo(1);
        assertThat(acquisitionService.listPackets("SES-A4")).hasSize(1);
        assertThat(operationService.trace("CARD-A4").torqueCurve()).hasSize(1);

        // 异内容：409
        Outcome conflict = acquisitionService.uploadBatch("SES-A4", TestRequests.batch(
                "b-a4-3", 3L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "101.000")));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) conflict.body()).get("errorCode"))
                .isEqualTo("PACKET_CONTENT_CONFLICT");
        assertThat(acquisitionService.listPackets("SES-A4")).hasSize(1);
    }

    @Test
    @DisplayName("关会话须无缺号且缓冲全部消费；归档前会话必须全部关闭")
    void closeRequiresConsumedAndArchiveRequiresClosed() {
        prepareSession("CARD-A5", "S-A5", "SES-A5");
        // 第二个会话在工序推进前创建，保持 OPEN 用于验证归档门禁
        Outcome created = acquisitionService.createSession(
                TestRequests.acqSession("s-a5-b", "SES-A5B", "CARD-A5", "T1"));
        assertThat(created.status()).isEqualTo(201);

        // 缺号：只缓冲了 2，关闭拒绝
        acquisitionService.uploadBatch("SES-A5", TestRequests.batch(
                "b-a5-1", 1L, TestRequests.packet(2, "P1", "2026-09-26T08:00:02Z", "100.000")));
        Outcome closeWithGap = acquisitionService.closeSession("SES-A5",
                TestRequests.close("c-a5-1", 2L));
        assertThat(closeWithGap.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) closeWithGap.body()).get("errorCode")).isEqualTo("SESSION_HAS_GAP");

        // 补 1 后缓冲消费完成，全部合格进入 TIGHTENED
        acquisitionService.uploadBatch("SES-A5", TestRequests.batch(
                "b-a5-2", 2L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(3, "P2", "2026-09-26T08:00:03Z", "50.000")));
        Outcome closed = acquisitionService.closeSession("SES-A5", TestRequests.close("c-a5-2", 3L));
        assertThat(closed.status()).isEqualTo(200);
        assertThat(((AcquisitionSessionView) closed.body()).status()).isEqualTo("CLOSED");

        // 未关闭会话阻挡归档
        operationService.inspect(TestRequests.inspection("i-a5", "CARD-A5", "a", "b"));
        Outcome releaseBlocked = operationService.release(
                TestRequests.release("r-a5-1", "CARD-A5", "qa"));
        assertThat(releaseBlocked.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) releaseBlocked.body()).get("errorCode"))
                .isEqualTo("ACQUISITION_SESSION_OPEN");

        // 关闭后会话归档放行
        Outcome closeB = acquisitionService.closeSession("SES-A5B", TestRequests.close("c-a5-b", 1L));
        assertThat(closeB.status()).isEqualTo(200);
        Outcome released = operationService.release(TestRequests.release("r-a5-2", "CARD-A5", "qa"));
        assertThat(released.status()).isEqualTo(200);
        assertThat(operationService.getCard("CARD-A5").status()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("归档后迟到包：只记迟到不复活卡片，力矩链不变")
    void latePacketsAfterArchive() {
        prepareSession("CARD-A6", "S-A6", "SES-A6");
        acquisitionService.uploadBatch("SES-A6", TestRequests.batch("b-a6-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(2, "P2", "2026-09-26T08:00:02Z", "50.000")));
        acquisitionService.closeSession("SES-A6", TestRequests.close("c-a6", 2L));
        operationService.inspect(TestRequests.inspection("i-a6", "CARD-A6", "a", "b"));
        operationService.release(TestRequests.release("r-a6", "CARD-A6", "qa"));
        assertThat(operationService.getCard("CARD-A6").status()).isEqualTo("ARCHIVED");

        // 归档后新序号包：记 LATE，不复活卡片
        Outcome late = acquisitionService.uploadBatch("SES-A6", TestRequests.batch(
                "b-a6-late", 3L, TestRequests.packet(3, "P1", "2026-09-26T09:00:01Z", "100.000")));
        assertThat(late.status()).isEqualTo(200);
        AcquisitionBatchResult lateResult = (AcquisitionBatchResult) late.body();
        assertThat(lateResult.late()).isEqualTo(1);
        assertThat(lateResult.committed()).isZero();
        assertThat(operationService.getCard("CARD-A6").status()).isEqualTo("ARCHIVED");
        // 力矩链仍 2 条，迟到事件 1 条，包证据标记 LATE
        assertThat(operationService.trace("CARD-A6").torqueCurve()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM late_measurement_event", Integer.class)).isEqualTo(1);
        assertThat(acquisitionService.listPackets("SES-A6"))
                .filteredOn(p -> p.seq() == 3)
                .extracting("status").containsExactly("LATE");
        // 水位线不变
        assertThat(acquisitionService.getWatermark("SES-A6").watermark()).isEqualTo(2);
    }

    @Test
    @DisplayName("前缀入账按测量时刻校验绑定工具日校验版本：跨天测量整批拒绝")
    void calibrationValidatedAtMeasurementTime() {
        prepareSession("CARD-A7", "S-A7", "SES-A7");
        // 测量日为 2026-09-27，绑定校验版本 2026-09-26：入账时拒绝，整批回滚
        Outcome outcome = acquisitionService.uploadBatch("SES-A7", TestRequests.batch(
                "b-a7-1", 1L, TestRequests.packet(1, "P1", "2026-09-27T00:00:01Z", "100.000")));
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outcome.body()).get("errorCode"))
                .isEqualTo("TOOL_CALIBRATION_EXPIRED");
        assertThat(acquisitionService.listPackets("SES-A7")).isEmpty();
        assertThat(acquisitionService.getWatermark("SES-A7").watermark()).isZero();
    }

    @Test
    @DisplayName("expectedVersion 版本冲突 409；成功变更加 1")
    void optimisticVersioning() {
        prepareSession("CARD-A8", "S-A8", "SES-A8");
        assertThat(acquisitionService.getSession("SES-A8").version()).isEqualTo(1);

        Outcome stale = acquisitionService.uploadBatch("SES-A8", TestRequests.batch(
                "b-a8-stale", 7L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) stale.body()).get("errorCode")).isEqualTo("VERSION_CONFLICT");

        Outcome ok = acquisitionService.uploadBatch("SES-A8", TestRequests.batch(
                "b-a8-1", 1L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000")));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(acquisitionService.getSession("SES-A8").version()).isEqualTo(2);

        Outcome closeStale = acquisitionService.closeSession("SES-A8",
                TestRequests.close("c-a8-stale", 1L));
        assertThat(closeStale.status()).isEqualTo(409);
        Outcome closeOk = acquisitionService.closeSession("SES-A8", TestRequests.close("c-a8", 2L));
        assertThat(closeOk.status()).isEqualTo(200);
        assertThat(acquisitionService.getSession("SES-A8").version()).isEqualTo(3);
    }

    @Test
    @DisplayName("采集超差沿用返工与隔离规则：2 次返工后第 3 次超差整批隔离")
    void reworkAndQuarantineViaAcquisition() {
        prepareSession("CARD-A9", "S-A9", "SES-A9");
        // 序 1~3：P1 三次超差（标称 100，±5%）
        Outcome outcome = acquisitionService.uploadBatch("SES-A9", TestRequests.batch("b-a9-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "120.000"),
                TestRequests.packet(2, "P1", "2026-09-26T08:01:01Z", "120.000"),
                TestRequests.packet(3, "P1", "2026-09-26T08:02:01Z", "120.000")));
        // 第 3 次仍超差触发整批隔离：入账部分保留，422 告知
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((AcquisitionBatchResult) outcome.body()).quarantined()).isTrue();
        assertThat(operationService.getCard("CARD-A9").status()).isEqualTo("QUARANTINED");
        assertThat(operationService.trace("CARD-A9").torqueCurve()).hasSize(3);
        assertThat(operationService.trace("CARD-A9").reworkLedger()).hasSize(2);
        assertThat(acquisitionService.getWatermark("SES-A9").watermark()).isEqualTo(3);
    }

    @Test
    @DisplayName("会话/批次/水位线/包证据查询；对象不存在 404")
    void queryEndpoints() {
        prepareSession("CARD-A10", "S-A10", "SES-A10");
        acquisitionService.uploadBatch("SES-A10", TestRequests.batch("b-a10-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"),
                TestRequests.packet(2, "P2", "2026-09-26T08:00:02Z", "50.000")));

        AcquisitionSessionView session = acquisitionService.getSession("SES-A10");
        assertThat(session.cardNo()).isEqualTo("CARD-A10");
        assertThat(session.toolCode()).isEqualTo("T1");
        assertThat(session.calibrationDate()).isEqualTo(TestRequests.TODAY);
        assertThat(session.pointCodes()).containsExactly("P1", "P2");
        assertThat(session.watermark()).isEqualTo(2);

        assertThat(acquisitionService.listBatches("SES-A10")).hasSize(1);
        assertThat(acquisitionService.listBatches("SES-A10").get(0).batchNo()).isEqualTo("b-a10-1");
        assertThat(acquisitionService.listPackets("SES-A10")).hasSize(2);
        assertThat(acquisitionService.listPackets("SES-A10").get(0).contentHash()).hasSize(64);

        try {
            acquisitionService.getSession("SES-MISSING");
            org.assertj.core.api.Assertions.fail("应抛出 404");
        } catch (com.example.starter.support.ApiException e) {
            assertThat(e.getStatus()).isEqualTo(404);
            assertThat(e.getErrorCode()).isEqualTo("SESSION_NOT_FOUND");
        }
    }
}
