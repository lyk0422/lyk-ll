package com.example.starter;

import com.example.starter.model.MaintenanceAcceptanceRole;
import com.example.starter.model.ReopenRole;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceCancelRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderModifyRequest;
import com.example.starter.web.dto.MaintenanceOrderRequest;
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
 * 检修封锁主流程与边界：窗口恰边界门禁、登记不自动清空、未清空拒绝验收、
 * 单人/同人验收拒绝、双阻断独立性、版本与幂等指纹、归档互不影响。
 */
class MaintenanceFlowTest extends BaseIntegrationTest {

    private static final Instant START = T0.plusSeconds(3600);
    private static final Instant END = START.plusSeconds(3600);

    private JsonNode createOrder(Instant start, Instant end, List<Long> segments) {
        ResponseEntity<String> response = maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(UUID.randomUUID().toString(), start, end,
                        "钢丝绳例行检修", segments));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private JsonNode createOrder() {
        return createOrder(START, END, List.of(SEGMENT_1, SEGMENT_2));
    }

    private JsonNode boardOk() {
        ResponseEntity<String> response = controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 3));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private void assertBoardingBlockedByMaintenance() {
        assertThatThrownBy(() -> controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 3)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessage()).contains("检修封锁");
                });
    }

    @Test
    void emptySegmentListRejected() {
        assertThatThrownBy(() -> maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(UUID.randomUUID().toString(), START, END, "检修", List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void crossLineSegmentsRejected() {
        assertThatThrownBy(() -> maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(UUID.randomUUID().toString(), START, END, "检修",
                        List.of(SEGMENT_1, SEGMENT_4))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(countRows("maintenance_order")).isZero();
    }

    @Test
    void overlappingWindowsRejectedButTouchingEndpointsAllowed() {
        createOrder(START, END, List.of(SEGMENT_1));
        // 内部重叠：拒绝
        assertThatThrownBy(() -> createOrder(START.plusSeconds(60), END.plusSeconds(60), List.of(SEGMENT_2)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        // 端点相接：[...,START) 与 [START,...) 允许
        JsonNode touching = createOrder(START.minusSeconds(1800), START, List.of(SEGMENT_2));
        assertThat(touching.get("id").asLong()).isPositive();
        // 终点恰接另一单起点也允许
        JsonNode after = createOrder(END, END.plusSeconds(1800), List.of(SEGMENT_3));
        assertThat(after.get("id").asLong()).isPositive();
        assertThat(countRows("maintenance_order")).isEqualTo(3);
    }

    @Test
    void boardingGateFollowsWindowBoundariesExactly() {
        createOrder();
        // start 前一刻：可上客，检修未生效
        clock.setFixed(START.minusSeconds(1));
        boardOk();
        // 恰 start：生效阻断
        clock.setFixed(START);
        assertBoardingBlockedByMaintenance();
        // 窗口中段：阻断
        clock.setFixed(START.plusSeconds(1800));
        assertBoardingBlockedByMaintenance();
        // 恰 end 未验收：仍封锁，不自动放行
        clock.setFixed(END);
        assertBoardingBlockedByMaintenance();
        // end 之后未验收：仍封锁
        clock.setFixed(END.plusSeconds(600));
        assertBoardingBlockedByMaintenance();
    }

    @Test
    void registeringOrderDoesNotAutoClearAndClearanceOnlyWhenEffective() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        // 生效前登记清空证据：拒绝
        clock.setFixed(START.minusSeconds(1));
        assertThatThrownBy(() -> registerClearance(orderId, SEGMENT_1, "evac-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 生效后清单分段无任何自动清空证据
        clock.setFixed(START);
        Integer cleared = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_clearance WHERE order_id = ?", Integer.class, orderId);
        assertThat(cleared).isZero();
        JsonNode c1 = registerClearance(orderId, SEGMENT_1, "evac-1");
        assertThat(c1.get("allCleared").asBoolean()).isFalse();
        assertThat(c1.get("clearedSegmentCount").asInt()).isEqualTo(1);
        // 同一分段重复留证不新增
        JsonNode again = registerClearance(orderId, SEGMENT_1, "evac-2");
        assertThat(again.get("clearedSegmentCount").asInt()).isEqualTo(1);
        assertThat(countRows("maintenance_clearance")).isEqualTo(1);
        registerClearance(orderId, SEGMENT_2, "evac-3");
    }

    @Test
    void acceptanceRejectedBeforeEndAndWhenUncleared() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        clock.setFixed(END.minusSeconds(1));
        // end 前即使清空也不能验收
        registerClearance(orderId, SEGMENT_1, "evac-1");
        registerClearance(orderId, SEGMENT_2, "evac-2");
        assertThatThrownBy(() -> accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 恰 end，但分段未全部清空：拒绝（新检修单场景：只清了一段再建一单）
        JsonNode order2 = createOrder(END, END.plusSeconds(1800), List.of(SEGMENT_3));
        long order2Id = order2.get("id").asLong();
        // order2 在 END 时刻生效，无清空证据：恰 end 验收拒绝
        clock.setFixed(END);
        assertThatThrownBy(() -> accept(order2Id, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(orderStatus(orderId)).isEqualTo("SCHEDULED");
    }

    @Test
    void singleRoleAndSamePersonDoNotCompleteButTwoDistinctPersonsCompleteAtEnd() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        clock.setFixed(START);
        registerClearance(orderId, SEGMENT_1, "evac-1");
        registerClearance(orderId, SEGMENT_2, "evac-2");

        // 恰 end：机务一人验收不完成
        clock.setFixed(END);
        JsonNode first = accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1");
        assertThat(first.get("completed").asBoolean()).isFalse();
        assertThat(first.get("mechanicalConfirmed").asBoolean()).isTrue();
        assertThat(first.get("repairConfirmed").asBoolean()).isFalse();
        assertBoardingBlockedByMaintenance();

        // 同一岗位重复验收：409
        assertThatThrownBy(() -> accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-9"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);

        // 另一岗位但同一人：422
        assertThatThrownBy(() -> accept(orderId, 1, MaintenanceAcceptanceRole.REPAIR, "mech-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 不同人员完成检修验收：恰 end 条件齐全，结束封锁且版本加 1
        JsonNode second = accept(orderId, 1, MaintenanceAcceptanceRole.REPAIR, "repair-1");
        assertThat(second.get("completed").asBoolean()).isTrue();
        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        // 检修只解除自身阻断：线路风速状态仍为 NORMAL，此时可上客
        assertThat(lineStatus()).isEqualTo("NORMAL");
        boardOk();
    }

    @Test
    void cancelOnlyBeforeEffective() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        clock.setFixed(START.minusSeconds(1));
        JsonNode cancelled = readJson(maintenanceService.cancelOrder(orderId,
                new MaintenanceCancelRequest(UUID.randomUUID().toString(), 1)).getBody());
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("version").asInt()).isEqualTo(2);
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        // 取消后到 start 不再阻断
        clock.setFixed(START);
        boardOk();

        // 生效后取消：拒绝
        JsonNode active = createOrder();
        long activeId = active.get("id").asLong();
        clock.setFixed(START.plusSeconds(60));
        assertThatThrownBy(() -> maintenanceService.cancelOrder(activeId,
                new MaintenanceCancelRequest(UUID.randomUUID().toString(), 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(orderStatus(activeId)).isEqualTo("SCHEDULED");
    }

    @Test
    void modifyUsesExpectedVersionAndFingerprintsVersion() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        // 旧版本：409
        assertThatThrownBy(() -> maintenanceService.modifyOrder(orderId,
                new MaintenanceOrderModifyRequest(UUID.randomUUID().toString(), 99,
                        START.plusSeconds(60), END.plusSeconds(60), "改期", List.of(SEGMENT_1))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(orderVersion(orderId)).isEqualTo(1);
        // 成功修改：版本 1 -> 2，窗口与清单被替换
        JsonNode modified = readJson(maintenanceService.modifyOrder(orderId,
                new MaintenanceOrderModifyRequest(UUID.randomUUID().toString(), 1,
                        START.plusSeconds(120), END.plusSeconds(120), "改期", List.of(SEGMENT_3))).getBody());
        assertThat(modified.get("version").asInt()).isEqualTo(2);
        assertThat(modified.get("reason").asText()).isEqualTo("改期");
        assertThat(modified.get("segmentIds")).hasSize(1);
        assertThat(modified.get("segmentIds").get(0).asLong()).isEqualTo(SEGMENT_3);
        assertThat(orderVersion(orderId)).isEqualTo(2);
        // 再以旧版本 1 提交：409
        assertThatThrownBy(() -> maintenanceService.modifyOrder(orderId,
                new MaintenanceOrderModifyRequest(UUID.randomUUID().toString(), 1,
                        START.plusSeconds(180), END.plusSeconds(180), "再次改期", List.of(SEGMENT_3))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("maintenance_segment")).isEqualTo(1);
    }

    @Test
    void idempotentReplayReplaysFirstResponseAndFailureDoesNotOccupyKey() {
        String requestId = UUID.randomUUID().toString();
        // 失败请求（空清单）不占用 requestId
        assertThatThrownBy(() -> maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(requestId, START, END, "检修", List.of())))
                .isInstanceOf(ApiException.class);
        // 同 requestId 改为合法参数：成功
        ResponseEntity<String> first = maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(requestId, START, END, "检修", List.of(SEGMENT_1)));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        // 同参重放：原响应
        ResponseEntity<String> replay = maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(requestId, START, END, "检修", List.of(SEGMENT_1)));
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        // 改参（清单变化）：409
        assertThatThrownBy(() -> maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(requestId, START, END, "检修", List.of(SEGMENT_2))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("maintenance_order")).isEqualTo(1);
    }

    @Test
    void dualBlockCompletionIndependentAndArchivesDoNotInterfere() {
        // 风速触发缓停
        feedMinutes(STATION_3, T0, 9, 17.0);
        feedMinutes(STATION_4, T0, 9, 17.0);
        JsonNode trig = ingest(STATION_3, T0.plusSeconds(600), 17.0);
        ingest(STATION_4, T0.plusSeconds(600), 17.0);
        long eventId = trig.get("triggeredEvent").get("eventId").asLong();
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");

        // 同一线路再生效检修单：双阻断
        JsonNode order = createOrder(T0.plusSeconds(900), T0.plusSeconds(7200), List.of(SEGMENT_1));
        long orderId = order.get("id").asLong();
        clock.setFixed(T0.plusSeconds(1000));
        // 风速阻断优先报错（状态非 NORMAL）
        assertThatThrownBy(() -> controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);

        // 检修完成只解除自身阻断：线路仍缓停
        registerClearance(orderId, SEGMENT_1, "evac-1");
        clock.setFixed(T0.plusSeconds(7200));
        accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1");
        JsonNode completed = accept(orderId, 1, MaintenanceAcceptanceRole.REPAIR, "repair-1");
        assertThat(completed.get("completed").asBoolean()).isTrue();
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        // 风控事件未被检修流程归档
        assertThat(eventStatus(eventId)).isNotEqualTo("RESUMED");

        // 走完原风速复开流程（低风 15 分钟 + 双岗），检修记录仍保留
        Instant triggerAt = T0.plusSeconds(600);

        // 缓停期间登记一张未生效检修单并取消：检修取消不归档风控事件
        Instant futureStart = Instant.now().plusSeconds(3600);
        JsonNode futureOrder = createOrder(futureStart, futureStart.plusSeconds(900), List.of(SEGMENT_2));
        long futureOrderId = futureOrder.get("id").asLong();
        clock.reset();
        readJson(maintenanceService.cancelOrder(futureOrderId,
                new MaintenanceCancelRequest(UUID.randomUUID().toString(), 1)).getBody());
        assertThat(orderStatus(futureOrderId)).isEqualTo("CANCELLED");
        assertThat(eventStatus(eventId)).isNotEqualTo("RESUMED");

        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, triggerAt.plusSeconds(60), 15, 14.0);
        }
        clock.reset();
        JsonNode applied = readJson(controlEventService.applyReopen(eventId,
                new com.example.starter.web.dto.ReopenApplicationRequest(
                        UUID.randomUUID().toString(), triggerAt.plusSeconds(16 * 60))).getBody());
        assertThat(applied.get("status").asText()).isEqualTo("REOPEN_REQUESTED");
        readJson(controlEventService.confirmReopen(eventId,
                new com.example.starter.web.dto.ReopenConfirmationRequest(
                        UUID.randomUUID().toString(), ReopenRole.OPERATIONS, "ops-1")).getBody());
        readJson(controlEventService.confirmReopen(eventId,
                new com.example.starter.web.dto.ReopenConfirmationRequest(
                        UUID.randomUUID().toString(), ReopenRole.MAINTENANCE, "mt-1")).getBody());
        assertThat(lineStatus()).isEqualTo("NORMAL");
        // 风控归档不删除检修阻断留证
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        assertThat(countRows("maintenance_clearance")).isEqualTo(1);
        boardOk();
    }

    @Test
    void lineWithoutMaintenanceOrderKeepsLegacyBehavior() {
        // 第二线路无检修单：正常上客
        ResponseEntity<String> response = controlEventService.board(LINE_2,
                new BoardingRequest(UUID.randomUUID().toString(), 2));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void viewsShowBothReasonsEvidenceAndOpinions() {
        JsonNode order = createOrder();
        long orderId = order.get("id").asLong();
        clock.setFixed(END);
        registerClearance(orderId, SEGMENT_1, "evac-1");
        registerClearance(orderId, SEGMENT_2, "evac-2");
        accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1", "机械部分正常");

        JsonNode detail = objectMapper.valueToTree(maintenanceService.orderDetail(orderId));
        assertThat(detail.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(detail.get("effective").asBoolean()).isTrue();
        assertThat(detail.get("reason").asText()).isEqualTo("钢丝绳例行检修");
        assertThat(detail.get("clearedSegments").asInt()).isEqualTo(2);
        assertThat(detail.get("clearances").get(0).get("operatorId").asText()).isEqualTo("evac-1");
        assertThat(detail.get("acceptances").get(0).get("opinion").asText()).isEqualTo("机械部分正常");

        JsonNode line = objectMapper.valueToTree(controlEventService.lineView(LINE_ID));
        assertThat(line.get("status").asText()).isEqualTo("NORMAL");
        assertThat(line.get("maintenanceBlocked").asBoolean()).isTrue();
        assertThat(line.get("activeMaintenanceOrderIds").get(0).asLong()).isEqualTo(orderId);
    }

    private JsonNode registerClearance(long orderId, long segmentId, String operatorId) {
        ResponseEntity<String> response = maintenanceService.registerClearance(orderId,
                new MaintenanceClearanceRequest(UUID.randomUUID().toString(), segmentId,
                        operatorId, "按原能力逐段疏散清空"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private JsonNode accept(long orderId, int expectedVersion, MaintenanceAcceptanceRole role,
                            String operatorId) {
        return accept(orderId, expectedVersion, role, operatorId, "验收合格");
    }

    private JsonNode accept(long orderId, int expectedVersion, MaintenanceAcceptanceRole role,
                            String operatorId, String opinion) {
        ResponseEntity<String> response = maintenanceService.accept(orderId,
                new MaintenanceAcceptanceRequest(UUID.randomUUID().toString(), expectedVersion,
                        role, operatorId, opinion));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM maintenance_order WHERE id = ?",
                String.class, orderId);
    }

    private int orderVersion(long orderId) {
        return jdbc.queryForObject("SELECT version FROM maintenance_order WHERE id = ?",
                Integer.class, orderId);
    }

    private String eventStatus(long eventId) {
        return jdbc.queryForObject("SELECT status FROM control_event WHERE id = ?",
                String.class, eventId);
    }
}
