package com.example.starter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.error.ApiException;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.telemetry.TelemetryDtos.TelemetryRequest;
import com.example.starter.telemetry.TelemetryDtos.TelemetryResponse;
import com.example.starter.telemetry.TelemetryService;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;
import com.example.starter.ticket.TicketDtos.TicketDetailView;
import com.example.starter.unlock.UnlockDtos.UnlockRequest;
import com.example.starter.unlock.UnlockDtos.UnlockResponse;
import com.example.starter.unlock.UnlockService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 倒闸操作票与防误闭锁服务集成测试：真实 H2（MODE=MySQL）验证约束、事务与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class SwitchingOrderIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private SwitchingOrderService orderService;
    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private UnlockService unlockService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM switching_step");
        jdbc.update("DELETE FROM switching_ticket");
        jdbc.update("DELETE FROM interlock_hit");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM interval_execution_lock");
        jdbc.update("DELETE FROM telemetry");
        jdbc.update("DELETE FROM unlock_record");
        jdbc.update("DELETE FROM ground_wire_operation");
        jdbc.update("DELETE FROM ground_wire_borrow");
        jdbc.update("DELETE FROM ground_wire");
        // 恢复合成接地线实物台账初始状态
        jdbc.update("INSERT INTO ground_wire (wire_no, status, version, created_at, updated_at) VALUES"
                + " ('GW-SYN-001','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GW-SYN-002','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GW-SYN-003','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
        // 恢复合成设备初始状态
        jdbc.update("UPDATE device_state SET state = 'CLOSED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id IN ('BR-1101','DS-1101','BR-F1','BR-F2')");
        jdbc.update("UPDATE device_state SET state = 'REMOVED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id = 'GW-1101'");
        jdbc.update("UPDATE device_state SET state = 'ENERGIZED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id = 'BUS-1'");
        clock.setInstant(T0);
    }

    // ---------- 主流程辅助 ----------

    private long draft(String requestId, String intervalId, StepSpec... steps) {
        return orderService.create(new CreateTicketRequest(requestId, intervalId, "测试操作票", List.of(steps)))
                .ticket().ticketId();
    }

    private long readyTicket(String createReq, String intervalId, StepSpec... steps) {
        long id = draft(createReq, intervalId, steps);
        orderService.review(id, new ReviewRequest("rv-" + createReq, "REV-01"));
        orderService.issue(id, new IssueRequest("iss-" + createReq));
        return id;
    }

    private static StepSpec step(String op, String device, String expected) {
        return new StepSpec(op, device, expected);
    }

    private ApiException expectConflict(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    // ---------- 防误闭锁：带负荷拉合刀闸 ----------

    @Test
    void loadDisconnectorBlocked() {
        long id = readyTicket("r-load", "INT-1101",
                step("OPEN_DISCONNECTOR", "DS-1101", "OPEN"));
        ApiException ex = expectConflict(() ->
                orderService.confirm(id, 1, new ConfirmStepRequest("r-load-c", "OP-01", "GD-01")));
        assertEquals("INTERLOCK_BLOCKED", ex.code());
        assertEquals("PENDING", orderService.get(id).steps().get(0).status());
        Map<String, Object> hit = jdbc.queryForMap(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ?", id);
        assertEquals("LOAD_DISCONNECTOR", hit.get("rule_code"));
        assertEquals("ISSUED", orderService.get(id).ticket().status());
    }

    // ---------- 防误闭锁：带电挂接地线 ----------

    @Test
    void liveGroundBlocked() {
        long id = readyTicket("r-live", "INT-1101",
                step("APPLY_GROUND", "GW-1101", "APPLIED"));
        ApiException ex = expectConflict(() ->
                orderService.confirm(id, 1, new ConfirmStepRequest("r-live-c", "OP-01", "GD-01")));
        assertEquals("INTERLOCK_BLOCKED", ex.code());
        assertEquals("LIVE_GROUND", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ?", String.class, id));
        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'GW-1101'", String.class));
    }

    // ---------- 防误闭锁：带地线送电 ----------

    @Test
    void groundedEnergizeBlocked() {
        jdbc.update("UPDATE device_state SET state = 'APPLIED' WHERE device_id = 'GW-1101'");
        long id = readyTicket("r-ground", "INT-1101",
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        ApiException ex = expectConflict(() ->
                orderService.confirm(id, 1, new ConfirmStepRequest("r-ground-c", "OP-01", "GD-01")));
        assertEquals("INTERLOCK_BLOCKED", ex.code());
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ?", String.class, id));
    }

    // ---------- 顺序违例：母线停电须先断开全部出线开关 ----------

    @Test
    void busOutageSequenceViolationRejectsStep() {
        long id = readyTicket("r-bus", "BUS-1", step("BUS_OUTAGE", "BUS-1", "OUTAGE"));
        ApiException ex = expectConflict(() ->
                orderService.confirm(id, 1, new ConfirmStepRequest("r-bus-c", "OP-01", "GD-01")));
        assertEquals("INTERLOCK_BLOCKED", ex.code());
        assertEquals("BUS_OUTAGE_SEQUENCE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ?", String.class, id));
        // 全部出线开关断开后顺序合规，操作放行
        jdbc.update("UPDATE device_state SET state = 'OPEN' WHERE device_id IN ('BR-F1','BR-F2')");
        var view = orderService.confirm(id, 1, new ConfirmStepRequest("r-bus-c2", "OP-01", "GD-01"));
        assertEquals("DONE", view.stepStatus());
    }

    // ---------- 双人确认：单人操作不生效 ----------

    @Test
    void singleOperatorConfirmRejected() {
        long id = readyTicket("r-single", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 1, new ConfirmStepRequest("r-single-c", "OP-01", "OP-01")));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", ex.code());
        assertEquals(400, ex.status().value());
        assertEquals("PENDING", orderService.get(id).steps().get(0).status());
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
    }

    // ---------- 逐项执行：跳步拒绝 ----------

    @Test
    void stepMustExecuteInOrder() {
        long id = readyTicket("r-order", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("OPEN_DISCONNECTOR", "DS-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 2, new ConfirmStepRequest("r-order-c", "OP-01", "GD-01")));
        assertEquals("STEP_ORDER", ex.code());
    }

    // ---------- 24 小时恰满作废，已执行步骤保留 ----------

    @Test
    void expiresExactlyAfter24HoursUtcAndKeepsExecutedSteps() {
        long id = readyTicket("r-exp", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("OPEN_DISCONNECTOR", "DS-1101", "OPEN"));
        // 23小时59分仍有效，第一步执行成功
        clock.plus(Duration.ofHours(23).plusMinutes(59));
        orderService.confirm(id, 1, new ConfirmStepRequest("r-exp-c1", "OP-01", "GD-01"));
        // 恰满24小时作废
        clock.plus(Duration.ofMinutes(1));
        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 2, new ConfirmStepRequest("r-exp-c2", "OP-01", "GD-01")));
        assertEquals("TICKET_EXPIRED", ex.code());
        TicketDetailView detail = orderService.get(id);
        assertEquals("EXPIRED", detail.ticket().status());
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("OP-01", detail.steps().get(0).operatorId());
        assertEquals("PENDING", detail.steps().get(1).status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        // 作废后允许归档
        orderService.archive(id, new ArchiveRequest("r-exp-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 遥信核对一致 ----------

    @Test
    void telemetryMatchedVerifiesStep() {
        long id = readyTicket("r-tm-ok", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("r-tm-ok-c", "OP-01", "GD-01"));
        TelemetryResponse resp = telemetryService.record(new TelemetryRequest(
                "INT-1101", "BR-1101", Instant.parse("2026-09-26T01:00:00Z"), "OPEN"));
        assertEquals("VERIFIED", resp.result());
        assertTrue(orderService.get(id).steps().get(0).verified());
    }

    // ---------- 遥信不符中止转异常 ----------

    @Test
    void telemetryMismatchAbortsTicket() {
        long id = readyTicket("r-tm-bad", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("r-tm-bad-c", "OP-01", "GD-01"));
        TelemetryResponse resp = telemetryService.record(new TelemetryRequest(
                "INT-1101", "BR-1101", Instant.parse("2026-09-26T01:00:00Z"), "CLOSED"));
        assertEquals("ABORTED", resp.result());
        assertEquals("EXCEPTION", orderService.get(id).ticket().status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        assertFalse(orderService.get(id).steps().get(0).verified());
    }

    // ---------- 遥信按间隔＋时标去重 ----------

    @Test
    void telemetryDeduplicatedByIntervalAndTimestamp() {
        TelemetryRequest req = new TelemetryRequest(
                "INT-1101", "BR-1101", Instant.parse("2026-09-26T02:00:00Z"), "OPEN");
        TelemetryResponse first = telemetryService.record(req);
        TelemetryResponse second = telemetryService.record(req);
        assertEquals("RECORDED", first.result());
        assertEquals("DEDUPLICATED", second.result());
        assertEquals(first.telemetryId(), second.telemetryId());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telemetry WHERE interval_id = 'INT-1101'", Integer.class);
        assertEquals(1, count);
    }

    // ---------- 并发预发：同间隔唯一成功 ----------

    @Test
    void concurrentIssueOnlyOneSucceedsForSameInterval() throws Exception {
        long ticketA = draft("r-con-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draft("r-con-b", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.review(ticketA, new ReviewRequest("rv-a", "REV-01"));
        orderService.review(ticketB, new ReviewRequest("rv-b", "REV-02"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable issueA = () -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            try {
                orderService.issue(ticketA, new IssueRequest("iss-con-a"));
                success.incrementAndGet();
            } catch (ApiException e) {
                if ("CONCURRENT_TICKET".equals(e.code())) {
                    conflict.incrementAndGet();
                }
            }
        };
        Runnable issueB = () -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            try {
                orderService.issue(ticketB, new IssueRequest("iss-con-b"));
                success.incrementAndGet();
            } catch (ApiException e) {
                if ("CONCURRENT_TICKET".equals(e.code())) {
                    conflict.incrementAndGet();
                }
            }
        };
        Future<?> f1 = pool.submit(issueA);
        Future<?> f2 = pool.submit(issueB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, success.get(), "并发预发必须唯一成功");
        assertEquals(1, conflict.get(), "失败方必须收到唯一冲突");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM interval_execution_lock WHERE interval_id = 'INT-1101'", Integer.class));
    }

    // ---------- 紧急解锁：双人授权且解锁/恢复成对留痕 ----------

    @Test
    void emergencyUnlockWritesUnlockAndRestoreAtomically() {
        UnlockResponse resp = unlockService.unlock(new UnlockRequest(
                "DS-1101", "LOAD_DISCONNECTOR", "AUTH-A", "AUTH-B", "紧急故障处理"));
        assertEquals("RESTORED", resp.status());
        List<String> actions = jdbc.query(
                "SELECT action FROM unlock_record WHERE device_id = 'DS-1101' ORDER BY id",
                (rs, n) -> rs.getString(1));
        assertEquals(List.of("UNLOCK", "RESTORE"), actions);
    }

    @Test
    void emergencyUnlockRequiresTwoAuthorizers() {
        ApiException ex = assertThrows(ApiException.class, () -> unlockService.unlock(new UnlockRequest(
                "DS-1101", "LOAD_DISCONNECTOR", "AUTH-A", "AUTH-A", "同一人")));
        assertEquals("DOUBLE_AUTH_REQUIRED", ex.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM unlock_record", Integer.class));
    }

    @Test
    void unlockRestoreAtomicityRollbackOnFailure() {
        // RESTORE 写入时触发 NOT NULL 约束，整事务回滚，不得只留解锁记录
        assertThrows(DataIntegrityViolationException.class, () -> unlockService.unlock(new UnlockRequest(
                "DS-1101", "LOAD_DISCONNECTOR", "AUTH-A", "AUTH-B", null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM unlock_record", Integer.class));
    }

    // ---------- 同键重放：同参返回首次结果，异参 409 ----------

    @Test
    void idempotentReplaySameKeySameParams() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "r-idem",
                "intervalId", "INT-1101",
                "title", "幂等票",
                "steps", List.of(Map.of(
                        "operationType", "OPEN_BREAKER",
                        "deviceId", "BR-1101",
                        "expectedState", "OPEN"))));
        MvcResult r1 = mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode n1 = mapper.readTree(r1.getResponse().getContentAsString());
        JsonNode n2 = mapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(n1.get("ticket").get("ticketId").asLong(),
                n2.get("ticket").get("ticketId").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM switching_ticket", Integer.class));
    }

    @Test
    void idempotentSameKeyDifferentParamsConflict() throws Exception {
        String body1 = mapper.writeValueAsString(Map.of(
                "requestId", "r-idem-x",
                "intervalId", "INT-1101",
                "title", "原标题",
                "steps", List.of(Map.of(
                        "operationType", "OPEN_BREAKER",
                        "deviceId", "BR-1101",
                        "expectedState", "OPEN"))));
        String body2 = mapper.writeValueAsString(Map.of(
                "requestId", "r-idem-x",
                "intervalId", "INT-1101",
                "title", "被篡改的标题",
                "steps", List.of(Map.of(
                        "operationType", "OPEN_BREAKER",
                        "deviceId", "BR-1101",
                        "expectedState", "OPEN"))));
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());
        MvcResult conflict = mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(conflict.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM switching_ticket", Integer.class));
    }

    @Test
    void stepConfirmIdempotentReplayExecutesOnce() throws Exception {
        long id = readyTicket("r-idem-step", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("OPEN_DISCONNECTOR", "DS-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "r-idem-step-c", "operatorId", "OP-01", "guardianId", "GD-01"));
        mockMvc.perform(post("/api/tickets/" + id + "/steps/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tickets/" + id + "/steps/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        TicketDetailView detail = orderService.get(id);
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("PENDING", detail.steps().get(1).status());
        assertNotNull(detail.steps().get(0).executedAt());
    }

    // ---------- 归档终态与迟到遥信 ----------

    @Test
    void archiveIsTerminalAndLateTelemetryRecordedOnly() {
        long id = readyTicket("r-arc", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("r-arc-c", "OP-01", "GD-01"));
        orderService.archive(id, new ArchiveRequest("r-arc-1"));
        // 重复归档幂等成功
        TicketDetailView again = orderService.archive(id, new ArchiveRequest("r-arc-2"));
        assertEquals("ARCHIVED", again.ticket().status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        // 归档后迟到遥信只记迟到事件
        TelemetryResponse late = telemetryService.record(new TelemetryRequest(
                "INT-1101", "BR-1101", Instant.parse("2026-09-26T03:00:00Z"), "OPEN"));
        assertEquals("LATE", late.result());
        assertTrue(jdbc.queryForObject(
                "SELECT late FROM telemetry WHERE interval_id = 'INT-1101'", Boolean.class));
        // 已执行步骤保持不变
        assertEquals("DONE", orderService.get(id).steps().get(0).status());
    }
}
