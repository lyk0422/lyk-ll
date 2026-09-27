package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CollectionBatchResult;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionWatermarkView;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 本地力矩采集连续水位线主流程与失败分支：
 * 序号 2 先到只缓冲、缺号不推进水位线、整批原子拒绝、同内容重放/异内容 409、
 * 关闭无缺号校验、归档前置门禁与归档后迟到。
 */
class CollectionWatermarkServiceTest extends AbstractServiceTest {

    private static final String CARD = "CARD-W1";
    private static final String STATION = "ST-W1";
    private static final String TOOL = "T1";
    private static final String SESSION = "SESS-W1";

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    /** 派工单点卡（标称 100，±5%）、登记当日工具、创建计划 expectedCount 包的采集会话。 */
    private CollectionSessionView openSession(int expectedCount) {
        operationService.registerTool(TestRequests.tool(TOOL, TestRequests.TODAY));
        operationService.dispatch(TestRequests.singlePoint(
                "d-" + CARD, CARD, STATION, "N", "P1", "100.000"));
        collectionService.openSession(TestRequests.session(
                "open-" + SESSION, SESSION, CARD, TOOL, expectedCount, List.of("P1")));
        return collectionService.getSession(SESSION);
    }

    private int torqueCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        return count == null ? 0 : count;
    }

    private int packetCount(String sessionNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_packet WHERE session_id = "
                        + "(SELECT id FROM collection_session WHERE session_no = ?)",
                Integer.class, sessionNo);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("序号 2、3 先到仅缓冲不产生力矩记录，序号 1 到达后整段连续前缀一次入账")
    void bufferOutOfOrderThenDrainPrefix() {
        openSession(4);

        Outcome buffered = collectionService.collect(TestRequests.batch(
                "b-23", SESSION, 1, List.of(
                        TestRequests.packet(2, "P1", "100"),
                        TestRequests.packet(3, "P1", "100"))));
        assertThat(buffered.status()).isEqualTo(200);
        CollectionBatchResult bufferedResult = (CollectionBatchResult) buffered.body();
        assertThat(bufferedResult.watermarkAfter()).isZero();
        assertThat(bufferedResult.bufferedSeqs()).containsExactly(2, 3);
        assertThat(bufferedResult.admittedSeqs()).isEmpty();
        assertThat(bufferedResult.version()).isEqualTo(2);
        // 缓冲包不产生力矩记录，不参与自检/互检/见证放行。
        assertThat(torqueCount()).isZero();
        CollectionWatermarkView wm = collectionService.getWatermark(SESSION);
        assertThat(wm.watermark()).isZero();
        assertThat(wm.bufferedSeqs()).containsExactly(2, 3);

        Outcome drained = collectionService.collect(TestRequests.batch(
                "b-1", SESSION, 2, List.of(TestRequests.packet(1, "P1", "100"))));
        CollectionBatchResult drainedResult = (CollectionBatchResult) drained.body();
        // 1 入账后，水位线沿连续前缀推进到 3（旧缓冲 2、3 同事务消费）。
        assertThat(drainedResult.watermarkAfter()).isEqualTo(3);
        assertThat(drainedResult.admittedSeqs()).containsExactly(1, 2, 3);
        assertThat(torqueCount()).isEqualTo(3);
        assertThat(collectionService.getWatermark(SESSION).bufferedSeqs()).isEmpty();
        for (CollectionPacketView p : collectionService.listPackets(SESSION)) {
            assertThat(p.state()).isEqualTo("ADMITTED");
            assertThat(p.attemptNo()).isEqualTo(p.seq());
        }
    }

    @Test
    @DisplayName("缺号阻断水位线：1、2 合格而 4 缓冲，水位线停在 2；4 不产生力矩记录")
    void gapStopsWatermark() {
        openSession(4);
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "b-gap", SESSION, 1, List.of(
                        TestRequests.packet(1, "P1", "100"),
                        TestRequests.packet(2, "P1", "100"),
                        TestRequests.packet(4, "P1", "100"))));
        CollectionBatchResult result = (CollectionBatchResult) outcome.body();
        assertThat(result.watermarkAfter()).isEqualTo(2);
        assertThat(result.admittedSeqs()).containsExactly(1, 2);
        assertThat(result.bufferedSeqs()).containsExactly(4);
        assertThat(torqueCount()).isEqualTo(2);
        assertThat(packetCount(SESSION)).isEqualTo(3);
        // 缺号未齐不能关闭。
        Outcome close = collectionService.close(TestRequests.close("c-gap", SESSION, 2));
        assertThat(close.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) close.body()).get("errorCode")).isEqualTo("SESSION_HAS_GAPS");
    }

    @Test
    @DisplayName("超出水位线后第 21 号窗口：整批 422，超窗包与同批有效包均不写入")
    void outOfWindowRejectsWholeBatch() {
        openSession(25);
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "b-win", SESSION, 1, List.of(
                        TestRequests.packet(20, "P1", "100"),
                        TestRequests.packet(21, "P1", "100"))));
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outcome.body()).get("errorCode")).isEqualTo("PACKET_OUT_OF_WINDOW");
        assertThat(packetCount(SESSION)).isZero();
        assertThat(torqueCount()).isZero();

        // 恰为第 20 号（windowHigh 边界）允许缓冲。
        Outcome edge = collectionService.collect(TestRequests.batch(
                "b-edge", SESSION, 1, List.of(TestRequests.packet(20, "P1", "100"))));
        assertThat(edge.status()).isEqualTo(200);
        assertThat(packetCount(SESSION)).isEqualTo(1);
    }

    @Test
    @DisplayName("超过计划总数的序号 422 拒绝")
    void seqBeyondPlanRejects() {
        openSession(2);
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "b-plan", SESSION, 1, List.of(TestRequests.packet(3, "P1", "100"))));
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outcome.body()).get("errorCode")).isEqualTo("PACKET_SEQ_BEYOND_PLAN");
        assertThat(packetCount(SESSION)).isZero();
    }

    @Test
    @DisplayName("未知点整批拒绝，不先写有效前半批")
    void unknownPointRejectsWholeBatch() {
        openSession(3);
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "b-unknown", SESSION, 1, List.of(
                        TestRequests.packet(1, "P1", "100"),
                        TestRequests.packet(2, "P9", "100"))));
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outcome.body()).get("errorCode")).isEqualTo("UNKNOWN_POINT");
        assertThat(packetCount(SESSION)).isZero();
        assertThat(torqueCount()).isZero();
    }

    @Test
    @DisplayName("批内序号重复 400 整批拒绝；一次 21 包 400")
    void duplicateInBatchAndBatchTooLarge() {
        openSession(30);
        Outcome duplicate = collectionService.collect(TestRequests.batch(
                "b-dup", SESSION, 1, List.of(
                        TestRequests.packet(1, "P1", "100"),
                        TestRequests.packet(1, "P1", "101"))));
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) duplicate.body()).get("errorCode")).isEqualTo("DUPLICATE_SEQ_IN_BATCH");

        List<com.example.starter.api.dto.CollectionPacketRequest> many = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            many.add(TestRequests.packet(i, "P1", "100"));
        }
        Outcome tooLarge = collectionService.collect(TestRequests.batch("b-many", SESSION, 1, many));
        assertThat(tooLarge.status()).isEqualTo(400);
        assertThat(packetCount(SESSION)).isZero();
    }

    @Test
    @DisplayName("同会话同序号同内容重放不追加、不推进版本，返回重放标记；异内容 409")
    void replaySameContentConflictDifferentContent() {
        openSession(2);
        var firstBatch = TestRequests.batch("b-replay", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100")));
        Outcome first = collectionService.collect(firstBatch);
        assertThat(first.status()).isEqualTo(200);
        assertThat(((CollectionBatchResult) first.body()).version()).isEqualTo(2);

        Outcome replay = collectionService.collect(firstBatch);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replay()).isTrue();
        assertThat(((CollectionBatchResult) replay.body()).version()).isEqualTo(2);
        assertThat(packetCount(SESSION)).isEqualTo(1);
        assertThat(torqueCount()).isEqualTo(1);

        Outcome conflict = collectionService.collect(TestRequests.batch(
                "b-replay", SESSION, 1, List.of(TestRequests.packet(1, "P1", "101"))));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) conflict.body()).get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        // 新 requestId 但序号已存在且内容不同 → 业务 409，不追加。
        Outcome different = collectionService.collect(TestRequests.batch(
                "b-different", SESSION, 2, List.of(TestRequests.packet(1, "P1", "99"))));
        assertThat(different.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) different.body()).get("errorCode")).isEqualTo("PACKET_SEQ_CONFLICT");
        assertThat(packetCount(SESSION)).isEqualTo(1);

        // 新包混已存在序号（即使内容相同）→ 整批 409，新包不写。
        Outcome mixed = collectionService.collect(TestRequests.batch(
                "b-mixed", SESSION, 2, List.of(
                        TestRequests.packet(1, "P1", "100"),
                        TestRequests.packet(2, "P1", "100"))));
        assertThat(mixed.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) mixed.body()).get("errorCode")).isEqualTo("PACKET_SEQ_EXISTS");
        assertThat(packetCount(SESSION)).isEqualTo(1);
    }

    @Test
    @DisplayName("失败不占键：同一 requestId 失败后可用原键提交合法批")
    void failureDoesNotOccupyKey() {
        openSession(3);
        var illegal = TestRequests.batch("b-retry", SESSION, 1,
                List.of(TestRequests.packet(1, "P9", "100")));
        Outcome failed = collectionService.collect(illegal);
        assertThat(failed.status()).isEqualTo(422);
        assertThat(packetCount(SESSION)).isZero();

        var legal = TestRequests.batch("b-retry", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100")));
        Outcome ok = collectionService.collect(legal);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(packetCount(SESSION)).isEqualTo(1);
    }

    @Test
    @DisplayName("expectedVersion 过期返回 409，数据不变；成功变更后版本 +1")
    void staleExpectedVersionConflict() {
        openSession(3);
        collectionService.collect(TestRequests.batch("b-v1", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100"))));
        Outcome stale = collectionService.collect(TestRequests.batch("b-stale", SESSION, 1,
                List.of(TestRequests.packet(2, "P1", "100"))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) stale.body()).get("errorCode")).isEqualTo("VERSION_CONFLICT");
        assertThat(collectionService.getSession(SESSION).version()).isEqualTo(2);
        assertThat(packetCount(SESSION)).isEqualTo(1);
    }

    @Test
    @DisplayName("工具日校验按测量时刻(UTC)复核：跨日测量 422 整批回滚")
    void toolCalibrationCheckedAtMeasuredInstant() {
        openSession(2);
        Instant nextDayUtc = TestRequests.MEASURE_BASE.plusSeconds(24L * 3600);
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "b-expired", SESSION, 1, List.of(
                        TestRequests.packetAt(1, "P1", "100", TestRequests.MEASURE_BASE),
                        TestRequests.packetAt(2, "P1", "100", nextDayUtc))));
        assertThat(outcome.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) outcome.body()).get("errorCode")).isEqualTo("TOOL_CALIBRATION_EXPIRED");
        // 同事务回滚：原始包与力矩记录均不存在。
        assertThat(packetCount(SESSION)).isZero();
        assertThat(torqueCount()).isZero();
    }

    @Test
    @DisplayName("绑定工具日校验非当日 422；不存在的卡 404")
    void openValidationAndNotFound() {
        operationService.dispatch(TestRequests.singlePoint("d-x", "CARD-X", "ST-X", "N", "P1", "100.000"));
        operationService.registerTool(TestRequests.tool("TX", TestRequests.TODAY.minusDays(1)));
        Outcome expiredTool = collectionService.openSession(TestRequests.session(
                "s-x", "SESS-X", "CARD-X", "TX", 1, List.of("P1")));
        assertThat(expiredTool.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) expiredTool.body()).get("errorCode")).isEqualTo("TOOL_CALIBRATION_EXPIRED");

        operationService.registerTool(TestRequests.tool("T404", TestRequests.TODAY));
        Outcome unknownCard = collectionService.openSession(TestRequests.session(
                "s-404", "SESS-404", "CARD-MISSING", "T404", 1, List.of("P1")));
        assertThat(unknownCard.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("无缺号且缓冲清空后关闭成功，关闭后再上传 422")
    void closeRequiresNoGapsAndNoBuffer() {
        openSession(2);
        collectionService.collect(TestRequests.batch("b-c1", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100"))));
        collectionService.collect(TestRequests.batch("b-c2", SESSION, 2,
                List.of(TestRequests.packet(2, "P1", "100"))));
        Outcome closed = collectionService.close(TestRequests.close("c-ok", SESSION, 3));
        assertThat(closed.status()).isEqualTo(200);
        CollectionSessionView view = (CollectionSessionView) closed.body();
        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.version()).isEqualTo(4);
        assertThat(view.watermark()).isEqualTo(2);

        Outcome afterClose = collectionService.collect(TestRequests.batch(
                "b-after", SESSION, 4, List.of(TestRequests.packet(1, "P1", "100"))));
        assertThat(afterClose.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) afterClose.body()).get("errorCode")).isEqualTo("SESSION_CLOSED");
    }

    @Test
    @DisplayName("归档前存在未关闭采集会话 422；全部关闭后归档成功")
    void archiveBlockedUntilSessionsClosed() {
        openSession(1);
        collectionService.collect(TestRequests.batch("b-a1", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100"))));
        operationService.inspect(TestRequests.inspection("i-w1", CARD, "self", "mutual"));

        Outcome blocked = operationService.release(TestRequests.release("r-blocked", CARD, "qa"));
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) blocked.body()).get("errorCode")).isEqualTo("COLLECTION_SESSION_OPEN");

        collectionService.close(TestRequests.close("c-a1", SESSION, 2));
        Outcome released = operationService.release(TestRequests.release("r-ok", CARD, "qa"));
        assertThat(released.status()).isEqualTo(200);
        assertThat(operationService.getCard(CARD).status()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("归档后上传包只记迟到（LATE + 迟到事件），不复活卡片、不变更会话版本")
    void latePacketsAfterArchive() {
        openSession(2);
        collectionService.collect(TestRequests.batch("b-l1", SESSION, 1,
                List.of(TestRequests.packet(1, "P1", "100"))));
        collectionService.collect(TestRequests.batch("b-l2", SESSION, 2,
                List.of(TestRequests.packet(2, "P1", "100"))));
        collectionService.close(TestRequests.close("c-l", SESSION, 3));
        operationService.inspect(TestRequests.inspection("i-l", CARD, "a", "b"));
        operationService.release(TestRequests.release("r-l", CARD, "qa"));

        int versionBefore = collectionService.getSession(SESSION).version();
        Outcome late = collectionService.collect(TestRequests.batch("b-late", SESSION, versionBefore,
                List.of(TestRequests.packetAt(2, "P1", "200",
                        TestRequests.MEASURE_BASE.plusSeconds(7200)))));
        assertThat(late.status()).isEqualTo(200);
        CollectionBatchResult lateResult = (CollectionBatchResult) late.body();
        assertThat(lateResult.lateSeqs()).containsExactly(2);
        assertThat(collectionService.getSession(SESSION).version()).isEqualTo(versionBefore);
        assertThat(operationService.getCard(CARD).status()).isEqualTo("ARCHIVED");
        CollectionPacketView packet = collectionService.listPackets(SESSION).stream()
                .filter(p -> p.seq() == 2 && p.state().equals("LATE")).findFirst().orElseThrow();
        assertThat(packet.attemptNo()).isNull();

        Integer lateEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM late_measurement_event WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(lateEvents).isEqualTo(1);
    }
}
