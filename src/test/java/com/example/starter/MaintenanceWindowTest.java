package com.example.starter;

import com.example.starter.model.AcceptanceRole;
import com.example.starter.model.ReopenRole;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.ClearanceRequest;
import com.example.starter.web.dto.LineView;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceCancelRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderCreateRequest;
import com.example.starter.web.dto.MaintenanceOrderView;
import com.example.starter.web.dto.ReopenApplicationRequest;
import com.example.starter.web.dto.ReopenConfirmationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检修封锁窗口测试：登记校验、恰边界生效、未生效取消、分段清空、双岗验收、
 * 版本冲突、幂等语义及与风控阻断的相互独立。
 */
class MaintenanceWindowTest extends BaseIntegrationTest {

    private static final Instant START = T0.plusSeconds(3600);
    private static final Instant END = T0.plusSeconds(3 * 3600);

    @Test
    void createValidatesSegmentListAndWindow() {
        // 空清单
        assertApiThrows(() -> createOrderRaw(List.of(), START, END), HttpStatus.UNPROCESSABLE_ENTITY);
        // 重复分段
        assertApiThrows(() -> createOrderRaw(List.of(SEGMENT_1, SEGMENT_1), START, END),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 跨线路分段（分段 4 属于线路 2）
        assertApiThrows(() -> createOrderRaw(List.of(SEGMENT_1, SEGMENT_4_LINE2), START, END),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 不存在的分段
        assertApiThrows(() -> createOrderRaw(List.of(999L), START, END),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 窗口起点不早于终点
        assertApiThrows(() -> createOrderRaw(List.of(SEGMENT_1), END, START),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiThrows(() -> createOrderRaw(List.of(SEGMENT_1), START, START),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(countRows("maintenance_order")).isZero();

        JsonNode created = createOrder(List.of(SEGMENT_1, SEGMENT_2), START, END);
        assertThat(created.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("segments")).hasSize(2);
    }

    @Test
    void overlappingWindowsRejectedButTouchingAllowed() {
        createOrder(List.of(SEGMENT_1), START, START.plusSeconds(7200));
        // 内部重叠拒绝
        assertApiThrows(() -> createOrderRaw(List.of(SEGMENT_2),
                START.plusSeconds(3600), START.plusSeconds(10800)), HttpStatus.CONFLICT);
        // 端点相接允许
        createOrder(List.of(SEGMENT_2), START.minusSeconds(3600), START);
        createOrder(List.of(SEGMENT_3), START.plusSeconds(7200), START.plusSeconds(14400));
        assertThat(countRows("maintenance_order")).isEqualTo(3);
    }

    @Test
    void cancelledOrderDoesNotBlockOverlap() {
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        timeProvider.useFixedClock(START.minusSeconds(60));
        cancel(order.get("id").asLong(), 1);
        // 已取消窗口不再参与重叠判定
        createOrder(List.of(SEGMENT_2), START.plusSeconds(1800), END);
    }

    @Test
    void boardingBlockedExactlyAtStartAndStaysBlockedPastEndWithoutAcceptance() {
        createOrder(List.of(SEGMENT_1), START, END);
        // 生效前允许上客
        timeProvider.useFixedClock(START.minusSeconds(1));
        boardOk();
        // 恰 start 即阻断
        timeProvider.useFixedClock(START);
        assertBoardConflict();
        // 到 end 未验收仍封锁，不自动放行
        timeProvider.useFixedClock(END.plusSeconds(3600));
        assertBoardConflict();
        assertThat(countRows("boarding")).isEqualTo(1);
    }

    @Test
    void cancelOnlyBeforeEffective() {
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        long orderId = order.get("id").asLong();
        // 已生效（恰 start）不可取消
        timeProvider.useFixedClock(START);
        assertApiThrows(() -> cancelRaw(orderId, 1), HttpStatus.CONFLICT);
        // 未生效可取消，版本加 1
        timeProvider.useFixedClock(START.minusSeconds(1));
        JsonNode cancelled = cancel(orderId, 1);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("version").asInt()).isEqualTo(2);
        // 已取消单不再阻断
        timeProvider.useFixedClock(START.plusSeconds(1800));
        boardOk();
        // 已取消单不可再取消
        assertApiThrows(() -> cancelRaw(orderId, 2), HttpStatus.CONFLICT);
    }

    @Test
    void clearanceRequiresEffectiveAndListedSegment() {
        JsonNode order = createOrder(List.of(SEGMENT_1, SEGMENT_2), START, END);
        long orderId = order.get("id").asLong();
        // 未生效不能登记清空
        timeProvider.useFixedClock(START.minusSeconds(1));
        assertApiThrows(() -> clearRaw(orderId, 1, SEGMENT_1, "op-a"), HttpStatus.CONFLICT);
        timeProvider.useFixedClock(START);
        // 清单外分段拒绝
        assertApiThrows(() -> clearRaw(orderId, 1, SEGMENT_3, "op-a"), HttpStatus.UNPROCESSABLE_ENTITY);
        // 逐段清空，版本递增
        JsonNode v2 = clearSegment(orderId, 1, SEGMENT_1, "op-a");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        // 同段重复清空（不同 requestId）拒绝
        assertApiThrows(() -> clearRaw(orderId, 2, SEGMENT_1, "op-b"), HttpStatus.CONFLICT);
        JsonNode v3 = clearSegment(orderId, 2, SEGMENT_2, "op-b");
        assertThat(v3.get("version").asInt()).isEqualTo(3);
    }

    @Test
    void acceptanceBoundaryExactlyAtEndAndRequiresTwoDifferentPeople() {
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START);
        clearSegment(orderId, 1, SEGMENT_1, "op-clear");
        // 验收早于 end 拒绝
        timeProvider.useFixedClock(END.minusSeconds(1));
        assertApiThrows(() -> acceptRaw(orderId, 2, AcceptanceRole.MECHANICAL, "mt-1"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 恰 end 且条件齐全可以验收
        timeProvider.useFixedClock(END);
        JsonNode v3 = accept(orderId, 2, AcceptanceRole.MECHANICAL, "mt-1");
        assertThat(v3.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(v3.get("version").asInt()).isEqualTo(3);
        // 仅单岗验收仍阻断
        assertBoardConflict();
        // 同一人员完成两岗拒绝
        assertApiThrows(() -> acceptRaw(orderId, 3, AcceptanceRole.INSPECTION, "mt-1"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 不同人员第二岗验收后完成，阻断解除
        JsonNode done = accept(orderId, 3, AcceptanceRole.INSPECTION, "insp-1");
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("version").asInt()).isEqualTo(4);
        boardOk();
    }

    @Test
    void acceptanceRequiresAllSegmentsCleared() {
        JsonNode order = createOrder(List.of(SEGMENT_1, SEGMENT_2), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START);
        clearSegment(orderId, 1, SEGMENT_1, "op-a");
        timeProvider.useFixedClock(END);
        // 分段 2 未清空，验收拒绝
        assertApiThrows(() -> acceptRaw(orderId, 2, AcceptanceRole.MECHANICAL, "mt-1"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        timeProvider.useFixedClock(END.minusSeconds(1800));
        clearSegment(orderId, 2, SEGMENT_2, "op-b");
        timeProvider.useFixedClock(END);
        JsonNode v4 = accept(orderId, 3, AcceptanceRole.MECHANICAL, "mt-1");
        assertThat(v4.get("version").asInt()).isEqualTo(4);
    }

    @Test
    void staleVersionReturns409() {
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START.minusSeconds(60));
        assertApiThrows(() -> cancelRaw(orderId, 5), HttpStatus.CONFLICT);
        JsonNode cancelled = cancel(orderId, 1);
        assertThat(cancelled.get("version").asInt()).isEqualTo(2);
        // 旧版本再次修改拒绝
        assertApiThrows(() -> cancelRaw(orderId, 1), HttpStatus.CONFLICT);
    }

    @Test
    void idempotencyReplayConflictAndFailureNotOccupying() {
        // 同键同参重放原响应
        String requestId = UUID.randomUUID().toString();
        JsonNode first = createOrder(requestId, List.of(SEGMENT_1), START, END);
        JsonNode replay = createOrder(requestId, List.of(SEGMENT_1), START, END);
        assertThat(replay).isEqualTo(first);
        assertThat(countRows("maintenance_order")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
        // 同键异参 409
        String finalRequestId = requestId;
        assertApiThrows(() -> createOrderRaw(finalRequestId, List.of(SEGMENT_1), START, END.plusSeconds(60)),
                HttpStatus.CONFLICT);

        // 失败不占键：重叠登记失败后，同 requestId 修正参数可成功
        String failedKey = UUID.randomUUID().toString();
        assertApiThrows(() -> createOrderRaw(failedKey, List.of(SEGMENT_2),
                START.plusSeconds(100), END.plusSeconds(100)), HttpStatus.CONFLICT);
        JsonNode fixed = createOrder(failedKey, List.of(SEGMENT_2), END, END.plusSeconds(3600));
        assertThat(fixed.get("version").asInt()).isEqualTo(1);

        // 未生效清空失败后，同 requestId 生效时再试成功
        long orderId = first.get("id").asLong();
        String clearKey = UUID.randomUUID().toString();
        timeProvider.useFixedClock(START.minusSeconds(60));
        assertApiThrows(() -> clearRaw(orderId, 1, SEGMENT_1, "op-a", clearKey), HttpStatus.CONFLICT);
        timeProvider.useFixedClock(START);
        JsonNode cleared = clearSegment(orderId, 1, SEGMENT_1, "op-a", clearKey);
        assertThat(cleared.get("version").asInt()).isEqualTo(2);

        // 取消成功重放：同键同参返回首次响应，不重复递增版本
        JsonNode future = createOrder(List.of(SEGMENT_3),
                START.plusSeconds(36000), START.plusSeconds(39600));
        long futureId = future.get("id").asLong();
        String cancelKey = UUID.randomUUID().toString();
        JsonNode cancelFirst = cancel(futureId, 1, cancelKey);
        JsonNode cancelReplay = cancel(futureId, 1, cancelKey);
        assertThat(cancelReplay).isEqualTo(cancelFirst);
        assertThat(maintenanceService.orderView(futureId).version()).isEqualTo(2);
    }

    @Test
    void dualBlockRequiresBothClearedIndependently() {
        // 检修先生效，再触发紧急停车：双重阻断分别留证
        JsonNode order = createOrder(List.of(SEGMENT_1, SEGMENT_2), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START);
        assertBoardConflict();

        Instant te = START.plusSeconds(600);
        feedMinutes(STATION_2, START, 10, 20.0);
        JsonNode resp = feedMinutes(STATION_3, START, 10, 20.0);
        long eventId = resp.get("triggeredEvent").get("eventId").asLong();
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");

        // 线路视图同时展示风控与检修两类阻断原因
        LineView dual = controlEventService.lineView(LINE_ID);
        assertThat(dual.status()).isEqualTo("EMERGENCY_STOP");
        assertThat(dual.activeEventId()).isEqualTo(eventId);
        assertThat(dual.maintenanceBlocked()).isTrue();
        assertThat(dual.maintenanceOrderId()).isEqualTo(orderId);
        assertBoardConflict();

        // 完成检修只解除自身阻断，风控阻断仍在
        clearSegment(orderId, 1, SEGMENT_1, "op-a");
        clearSegment(orderId, 2, SEGMENT_2, "op-b");
        timeProvider.useFixedClock(END);
        accept(orderId, 3, AcceptanceRole.MECHANICAL, "mt-1");
        accept(orderId, 4, AcceptanceRole.INSPECTION, "insp-1");
        assertThat(controlEventService.lineView(LINE_ID).maintenanceBlocked()).isFalse();
        assertBoardConflict();

        // 原救援、低风 15 分钟与复开双岗确认仍须满足
        for (long segment : new long[]{SEGMENT_1, SEGMENT_2, SEGMENT_3}) {
            confirmClearance(eventId, segment, "rescuer-a");
            confirmClearance(eventId, segment, "rescuer-b");
        }
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, te.plusSeconds(60), 15, 12.0);
        }
        controlEventService.applyReopen(eventId,
                new ReopenApplicationRequest(UUID.randomUUID().toString(), te.plusSeconds(16 * 60)));
        confirmReopen(eventId, ReopenRole.OPERATIONS, "ops-1");
        confirmReopen(eventId, ReopenRole.MAINTENANCE, "mt-9");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        boardOk();
    }

    @Test
    void windArchiveKeepsMaintenanceAndCancelKeepsWindEvent() {
        // 登记未来窗口，随后缓停触发并复开归档
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        long orderId = order.get("id").asLong();
        feedMinutes(STATION_3, T0, 10, 17.0);
        feedMinutes(STATION_4, T0, 10, 17.0);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
        long eventId = jdbc.queryForObject("SELECT id FROM control_event", Long.class);
        Instant triggerAt = T0.plusSeconds(600);
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, triggerAt.plusSeconds(60), 15, 12.0);
        }
        timeProvider.useFixedClock(T0.plusSeconds(2000));
        controlEventService.applyReopen(eventId,
                new ReopenApplicationRequest(UUID.randomUUID().toString(), triggerAt.plusSeconds(16 * 60)));
        confirmReopen(eventId, ReopenRole.OPERATIONS, "ops-1");
        confirmReopen(eventId, ReopenRole.MAINTENANCE, "mt-1");
        assertThat(lineStatus()).isEqualTo("NORMAL");

        // 风控事件归档不删除检修阻断：到 start 仍阻断上客
        timeProvider.useFixedClock(START);
        assertBoardConflict();
        LineView view = controlEventService.lineView(LINE_ID);
        assertThat(view.status()).isEqualTo("NORMAL");
        assertThat(view.activeEventId()).isNull();
        assertThat(view.maintenanceBlocked()).isTrue();
        assertThat(view.maintenanceOrderId()).isEqualTo(orderId);

        // 再次触发缓停后，取消另一张未生效检修单不归档风控事件
        Instant t1 = T0.plusSeconds(7200);
        feedMinutes(STATION_3, t1, 10, 17.0);
        feedMinutes(STATION_4, t1, 10, 17.0);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
        long activeEventId = jdbc.queryForObject(
                "SELECT id FROM control_event WHERE status = 'ACTIVE'", Long.class);
        timeProvider.useFixedClock(t1);
        JsonNode order2 = createOrder(List.of(SEGMENT_2),
                t1.plusSeconds(7200), t1.plusSeconds(10800));
        cancel(order2.get("id").asLong(), 1);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
        assertThat(jdbc.queryForObject("SELECT status FROM control_event WHERE id = ?",
                String.class, activeEventId)).isEqualTo("ACTIVE");
    }

    @Test
    void reopenConfirmationBlockedByMaintenance() {
        // 缓停并满足低风 15 分钟，申请复开
        feedMinutes(STATION_3, T0, 10, 17.0);
        feedMinutes(STATION_4, T0, 10, 17.0);
        long eventId = jdbc.queryForObject("SELECT id FROM control_event", Long.class);
        Instant triggerAt = T0.plusSeconds(600);
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, triggerAt.plusSeconds(60), 15, 12.0);
        }
        timeProvider.useFixedClock(T0.plusSeconds(2000));
        controlEventService.applyReopen(eventId,
                new ReopenApplicationRequest(UUID.randomUUID().toString(), triggerAt.plusSeconds(16 * 60)));

        // 检修生效期间复开确认拒绝
        JsonNode order = createOrder(List.of(SEGMENT_1), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START);
        assertApiThrows(() -> confirmReopen(eventId, ReopenRole.OPERATIONS, "ops-1"), HttpStatus.CONFLICT);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");

        // 检修验收完成后复开双岗确认可推进
        clearSegment(orderId, 1, SEGMENT_1, "op-c");
        timeProvider.useFixedClock(END);
        accept(orderId, 2, AcceptanceRole.MECHANICAL, "mt-1");
        accept(orderId, 3, AcceptanceRole.INSPECTION, "insp-1");
        confirmReopen(eventId, ReopenRole.OPERATIONS, "ops-1");
        confirmReopen(eventId, ReopenRole.MAINTENANCE, "mt-2");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        boardOk();
    }

    @Test
    void orderViewShowsClearanceEvidenceAndAcceptanceOpinions() {
        JsonNode order = createOrder(List.of(SEGMENT_1, SEGMENT_2), START, END);
        long orderId = order.get("id").asLong();
        timeProvider.useFixedClock(START);
        clearSegment(orderId, 1, SEGMENT_1, "clear-a");
        clearSegment(orderId, 2, SEGMENT_2, "clear-b");
        timeProvider.useFixedClock(END);
        accept(orderId, 3, AcceptanceRole.MECHANICAL, "mech-op");
        accept(orderId, 4, AcceptanceRole.INSPECTION, "insp-op");

        MaintenanceOrderView view = maintenanceService.orderView(orderId);
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.version()).isEqualTo(5);
        assertThat(view.blocked()).isFalse();
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.segments()).hasSize(2);
        assertThat(view.segments()).allSatisfy(s -> {
            assertThat(s.cleared()).isTrue();
            assertThat(s.clearedBy()).isNotBlank();
            assertThat(s.clearedAt()).isNotNull();
        });
        assertThat(view.acceptances()).hasSize(2);
        assertThat(view.acceptances()).extracting(MaintenanceOrderView.AcceptanceView::role)
                .containsExactlyInAnyOrder("MECHANICAL", "INSPECTION");
        assertThat(view.acceptances()).extracting(MaintenanceOrderView.AcceptanceView::operatorId)
                .containsExactlyInAnyOrder("mech-op", "insp-op");
        assertThat(view.acceptances()).allSatisfy(a -> assertThat(a.opinion()).isNotBlank());
    }

    // ---------- 辅助方法 ----------

    private JsonNode createOrder(List<Long> segmentIds, Instant start, Instant end) {
        return createOrder(UUID.randomUUID().toString(), segmentIds, start, end);
    }

    private JsonNode createOrder(String requestId, List<Long> segmentIds, Instant start, Instant end) {
        ResponseEntity<String> response = createOrderRaw(requestId, segmentIds, start, end);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private ResponseEntity<String> createOrderRaw(List<Long> segmentIds, Instant start, Instant end) {
        return createOrderRaw(UUID.randomUUID().toString(), segmentIds, start, end);
    }

    private ResponseEntity<String> createOrderRaw(String requestId, List<Long> segmentIds,
                                                  Instant start, Instant end) {
        return maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderCreateRequest(requestId, segmentIds, start, end));
    }

    private JsonNode cancel(long orderId, int expectedVersion) {
        return cancel(orderId, expectedVersion, UUID.randomUUID().toString());
    }

    private JsonNode cancel(long orderId, int expectedVersion, String requestId) {
        ResponseEntity<String> response = maintenanceService.cancel(orderId,
                new MaintenanceCancelRequest(requestId, expectedVersion, "op-cancel"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private ResponseEntity<String> cancelRaw(long orderId, int expectedVersion) {
        return maintenanceService.cancel(orderId, new MaintenanceCancelRequest(
                UUID.randomUUID().toString(), expectedVersion, "op-cancel"));
    }

    private JsonNode clearSegment(long orderId, int expectedVersion, long segmentId, String operatorId) {
        return clearSegment(orderId, expectedVersion, segmentId, operatorId, UUID.randomUUID().toString());
    }

    private JsonNode clearSegment(long orderId, int expectedVersion, long segmentId, String operatorId,
                                  String requestId) {
        ResponseEntity<String> response = maintenanceService.recordClearance(orderId,
                new MaintenanceClearanceRequest(requestId, expectedVersion, segmentId, operatorId, "已疏散"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private ResponseEntity<String> clearRaw(long orderId, int expectedVersion, long segmentId, String operatorId) {
        return clearRaw(orderId, expectedVersion, segmentId, operatorId, UUID.randomUUID().toString());
    }

    private ResponseEntity<String> clearRaw(long orderId, int expectedVersion, long segmentId, String operatorId,
                                            String requestId) {
        return maintenanceService.recordClearance(orderId,
                new MaintenanceClearanceRequest(requestId, expectedVersion, segmentId, operatorId, "已疏散"));
    }

    private JsonNode accept(long orderId, int expectedVersion, AcceptanceRole role, String operatorId) {
        ResponseEntity<String> response = acceptRaw(orderId, expectedVersion, role, operatorId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private ResponseEntity<String> acceptRaw(long orderId, int expectedVersion, AcceptanceRole role,
                                             String operatorId) {
        return maintenanceService.accept(orderId, new MaintenanceAcceptanceRequest(
                UUID.randomUUID().toString(), expectedVersion, role, operatorId, "验收合格"));
    }

    private void confirmClearance(long eventId, long segmentId, String confirmerId) {
        ResponseEntity<String> response = controlEventService.confirmClearance(eventId, segmentId,
                new ClearanceRequest(UUID.randomUUID().toString(), confirmerId));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void confirmReopen(long eventId, ReopenRole role, String operatorId) {
        ResponseEntity<String> response = controlEventService.confirmReopen(eventId,
                new ReopenConfirmationRequest(UUID.randomUUID().toString(), role, operatorId));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void boardOk() {
        ResponseEntity<String> response = controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 1));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void assertBoardConflict() {
        assertApiThrows(() -> controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 1)), HttpStatus.CONFLICT);
    }

    private void assertApiThrows(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(status);
    }
}
