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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.error.ApiException;
import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowItem;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireDetailView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireView;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.groundwire.GroundWireService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.telemetry.TelemetryDtos.TelemetryRequest;
import com.example.starter.telemetry.TelemetryService;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 临时接地线借用核销与送电门禁集成测试：真实 H2（MODE=MySQL）验证
 * 状态机、双人确认证据、版本乐观锁、送电/归档门禁及并发与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class GroundWireIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private SwitchingOrderService orderService;
    @Autowired
    private GroundWireService groundWireService;
    @Autowired
    private TelemetryService telemetryService;
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
        jdbc.update("DELETE FROM ground_wire_event");
        jdbc.update("DELETE FROM ground_wire_loan");
        jdbc.update("DELETE FROM ground_wire");
        jdbc.update("INSERT INTO ground_wire (wire_no, status, version, created_at, updated_at) VALUES"
                + " ('GL-0001','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GL-0002','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GL-0003','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GL-0004','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
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
        return orderService.create(new CreateTicketRequest(requestId, intervalId, "接地线测试票", List.of(steps)))
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

    private static BorrowItem item(String wireNo, int version) {
        return new BorrowItem(wireNo, version);
    }

    private String wireStatus(String wireNo) {
        return jdbc.queryForObject("SELECT status FROM ground_wire WHERE wire_no = ?", String.class, wireNo);
    }

    private int wireVersion(String wireNo) {
        return jdbc.queryForObject("SELECT version FROM ground_wire WHERE wire_no = ?", Integer.class, wireNo);
    }

    // ---------- 主流程：借用→挂接→送电门禁→拆除→归还→送电→归档 ----------

    @Test
    void fullCycleBorrowApplyRemoveReturnThenEnergizeAndArchive() throws Exception {
        long id = readyTicket("gw-main", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        // 断开开关后间隔不带电，允许挂接
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-main-c1", "OP-01", "GD-01"));

        // 批量借用两条，版本 1 → 2
        BorrowView borrowed = groundWireService.borrow(new BorrowRequest(
                "gw-main-b", id, "INT-1101", null, List.of(item("GL-0001", 1), item("GL-0002", 1))));
        assertEquals(2, borrowed.wires().size());
        assertEquals("BORROWED", wireStatus("GL-0001"));
        assertEquals(2, wireVersion("GL-0001"));
        assertEquals(2, groundWireService.list(id).size());

        // 挂接：双人确认，引用本票步骤，版本 2 → 3，证据时刻取请求精确时刻
        Instant applyAt = T0.plus(Duration.ofHours(1));
        GroundWireView applied = groundWireService.apply("GL-0001", new ApplyRequest(
                "gw-main-a1", id, 2, "OP-01", "GD-01", 2, applyAt));
        assertEquals("APPLIED", applied.status());
        assertEquals(3, applied.version());
        groundWireService.apply("GL-0002", new ApplyRequest(
                "gw-main-a2", id, 2, "OP-02", "GD-02", 2, null));

        // 送电门禁：仍有挂接，拒绝且不推进步序
        ApiException gate = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 2, new ConfirmStepRequest("gw-main-c2", "OP-01", "GD-01")));
        assertEquals("ENERGIZE_GROUND_ATTACHED", gate.code());
        assertEquals(422, gate.status().value());
        assertEquals("PENDING", orderService.get(id).steps().get(1).status());
        assertEquals("TEMP_GROUND_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? AND step_seq = 2", String.class, id));

        // 拆除：双人确认，版本 3 → 4
        Instant removeAt = T0.plus(Duration.ofHours(2));
        GroundWireView removed = groundWireService.remove("GL-0001", new RemoveRequest(
                "gw-main-r1", id, 2, "OP-01", "GD-01", 3, removeAt));
        assertEquals("REMOVED_PENDING_RETURN", removed.status());
        groundWireService.remove("GL-0002", new RemoveRequest(
                "gw-main-r2", id, 2, "OP-02", "GD-02", 3, null));

        // 归还核销：仓管核对编号，版本 4 → 5，实物恢复可用
        GroundWireView returned = groundWireService.returnWire("GL-0001", new ReturnRequest(
                "gw-main-t1", "KP-01", 4, null));
        assertEquals("AVAILABLE", returned.status());
        assertEquals(5, returned.version());
        assertNull(returned.ticketId());
        groundWireService.returnWire("GL-0002", new ReturnRequest("gw-main-t2", "KP-01", 4, null));

        // 证据与借用历史保留：挂接/拆除各一条，时刻为请求精确时刻，借用单已核销
        MvcResult detailResult = mockMvc.perform(get("/api/ground-wires/GL-0001"))
                .andExpect(status().isOk()).andReturn();
        GroundWireDetailView detail = mapper.readValue(
                detailResult.getResponse().getContentAsString(), GroundWireDetailView.class);
        assertEquals(2, detail.events().size());
        assertEquals("APPLY", detail.events().get(0).action());
        assertEquals("OP-01", detail.events().get(0).operatorId());
        assertEquals("GD-01", detail.events().get(0).guardianId());
        assertEquals(applyAt, detail.events().get(0).createdAt());
        assertEquals("REMOVE", detail.events().get(1).action());
        assertEquals(removeAt, detail.events().get(1).createdAt());
        assertEquals(1, detail.loans().size());
        assertEquals("KP-01", detail.loans().get(0).keeperId());
        assertNotNull(detail.loans().get(0).returnedAt());

        // 全部归还后送电放行，归档成功
        orderService.confirm(id, 2, new ConfirmStepRequest("gw-main-c3", "OP-01", "GD-01"));
        assertEquals("DONE", orderService.get(id).steps().get(1).status());
        orderService.archive(id, new ArchiveRequest("gw-main-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 借用失败分支 ----------

    @Test
    void borrowTicketNotFound() {
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-404", 99999L, "INT-1101", null, List.of(item("GL-0001", 1)))));
        assertEquals("TICKET_NOT_FOUND", ex.code());
        assertEquals(404, ex.status().value());
    }

    @Test
    void borrowArchivedTicketRejected() {
        long id = readyTicket("b-arc", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.archive(id, new ArchiveRequest("b-arc-1"));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-arc-2", id, "INT-1101", null, List.of(item("GL-0001", 1)))));
        assertEquals("TICKET_STATE", ex.code());
        assertEquals(422, ex.status().value());
    }

    @Test
    void borrowExpiredTicketRejected() {
        long id = readyTicket("b-exp", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        clock.plus(Duration.ofHours(24));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-exp-1", id, "INT-1101", null, List.of(item("GL-0001", 1)))));
        assertEquals("TICKET_EXPIRED", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("AVAILABLE", wireStatus("GL-0001"));
    }

    @Test
    void borrowIntervalMismatchRejected() {
        long id = readyTicket("b-int", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-int-1", id, "INT-9999", null, List.of(item("GL-0001", 1)))));
        assertEquals("BORROW_INTERVAL_MISMATCH", ex.code());
        assertEquals(422, ex.status().value());
    }

    @Test
    void borrowBatchRejectedWhenAnyUnavailable() {
        long ticketA = readyTicket("b-batch-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest(
                "b-batch-1", ticketA, "INT-1101", null, List.of(item("GL-0001", 1))));
        long ticketB = draft("b-batch-b", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        // GL-0002 可用但 GL-0001 已借出：整批拒绝，GL-0002 不得被占用
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-batch-2", ticketB, "INT-1102", null,
                        List.of(item("GL-0002", 1), item("GL-0001", 1)))));
        assertEquals("WIRE_NOT_AVAILABLE", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("AVAILABLE", wireStatus("GL-0002"));
        assertEquals(1, wireVersion("GL-0002"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_loan WHERE ticket_id = ?", Integer.class, ticketB));
    }

    @Test
    void borrowUnknownWireNotFound() {
        long id = readyTicket("b-nf", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-nf-1", id, "INT-1101", null, List.of(item("GL-9999", 1)))));
        assertEquals("WIRE_NOT_FOUND", ex.code());
        assertEquals(404, ex.status().value());
    }

    @Test
    void borrowVersionConflict() {
        long id = readyTicket("b-ver", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-ver-1", id, "INT-1101", null, List.of(item("GL-0001", 99)))));
        assertEquals("VERSION_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals("AVAILABLE", wireStatus("GL-0001"));
    }

    @Test
    void borrowDuplicateWireRejected() {
        long id = readyTicket("b-dup", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.borrow(
                new BorrowRequest("b-dup-1", id, "INT-1101", null,
                        List.of(item("GL-0001", 1), item("GL-0001", 1)))));
        assertEquals("DUPLICATE_WIRE", ex.code());
        assertEquals(400, ex.status().value());
    }

    // ---------- 挂接/拆除/归还失败分支 ----------

    @Test
    void applyRequiresTwoDistinctPersons() {
        long id = readyTicket("a-dbl", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest("a-dbl-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.apply("GL-0001",
                new ApplyRequest("a-dbl-1", id, 1, "OP-01", "OP-01", 2, null)));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", ex.code());
        assertEquals(400, ex.status().value());
        assertEquals("BORROWED", wireStatus("GL-0001"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_event", Integer.class));
    }

    @Test
    void applyRejectsWireBoundToOtherTicket() {
        long ticketA = readyTicket("a-oth-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draft("a-oth-b", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest(
                "a-oth-b1", ticketA, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.apply("GL-0001",
                new ApplyRequest("a-oth-1", ticketB, 1, "OP-01", "GD-01", 2, null)));
        assertEquals("WIRE_TICKET_MISMATCH", ex.code());
        assertEquals(422, ex.status().value());
    }

    @Test
    void applyStepNotFound() {
        long id = readyTicket("a-stp", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest("a-stp-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.apply("GL-0001",
                new ApplyRequest("a-stp-1", id, 9, "OP-01", "GD-01", 2, null)));
        assertEquals("STEP_NOT_FOUND", ex.code());
        assertEquals(404, ex.status().value());
    }

    @Test
    void applyVersionConflict() {
        long id = readyTicket("a-ver", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest("a-ver-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.apply("GL-0001",
                new ApplyRequest("a-ver-1", id, 1, "OP-01", "GD-01", 7, null)));
        assertEquals("VERSION_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals("BORROWED", wireStatus("GL-0001"));
    }

    @Test
    void removeRejectsNotApplied() {
        long id = readyTicket("r-st", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest("r-st-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () -> groundWireService.remove("GL-0001",
                new RemoveRequest("r-st-1", id, 1, "OP-01", "GD-01", 2, null)));
        assertEquals("WIRE_STATE", ex.code());
        assertEquals(422, ex.status().value());
    }

    @Test
    void returnRejectsNotRemoved() {
        long id = readyTicket("t-st", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("t-st-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("t-st-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        // 借出未挂接：不可核销
        ApiException borrowed = assertThrows(ApiException.class, () -> groundWireService.returnWire(
                "GL-0001", new ReturnRequest("t-st-1", "KP-01", 2, null)));
        assertEquals("WIRE_STATE", borrowed.code());
        assertEquals(422, borrowed.status().value());
        // 已挂接未拆除：不可核销
        groundWireService.apply("GL-0001", new ApplyRequest("t-st-a", id, 1, "OP-01", "GD-01", 2, null));
        ApiException applied = assertThrows(ApiException.class, () -> groundWireService.returnWire(
                "GL-0001", new ReturnRequest("t-st-2", "KP-01", 3, null)));
        assertEquals("WIRE_STATE", applied.code());
        assertEquals("APPLIED", wireStatus("GL-0001"));
    }

    // ---------- 归档门禁与作废/异常不自动恢复 ----------

    @Test
    void archiveBlockedUntilAllWiresReturned() {
        long id = readyTicket("arc-g", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("arc-g-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("arc-g-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.archive(id, new ArchiveRequest("arc-g-1")));
        assertEquals("GROUND_WIRE_OUTSTANDING", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("EXECUTING", orderService.get(id).ticket().status());
        // 合法拆除并归还后归档放行
        groundWireService.apply("GL-0001", new ApplyRequest("arc-g-a", id, 1, "OP-01", "GD-01", 2, null));
        groundWireService.remove("GL-0001", new RemoveRequest("arc-g-r", id, 1, "OP-01", "GD-01", 3, null));
        groundWireService.returnWire("GL-0001", new ReturnRequest("arc-g-t", "KP-01", 4, null));
        orderService.archive(id, new ArchiveRequest("arc-g-2"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
        // 历史借用记录保留
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_loan WHERE ticket_id = ?", Integer.class, id));
    }

    @Test
    void exceptionTicketKeepsWireAppliedAndAllowsRemoveReturn() {
        long id = readyTicket("exc-g", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("exc-g-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("exc-g-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        groundWireService.apply("GL-0001", new ApplyRequest("exc-g-a", id, 1, "OP-01", "GD-01", 2, null));
        // 遥信不符中止：票转异常，挂接实物不得自动恢复可用
        telemetryService.record(new TelemetryRequest(
                "INT-1101", "BR-1101", T0.plus(Duration.ofHours(1)), "CLOSED"));
        assertEquals("EXCEPTION", orderService.get(id).ticket().status());
        assertEquals("APPLIED", wireStatus("GL-0001"));
        // 仍须合法拆除与归还
        groundWireService.remove("GL-0001", new RemoveRequest("exc-g-r", id, 1, "OP-01", "GD-01", 3, null));
        groundWireService.returnWire("GL-0001", new ReturnRequest("exc-g-t", "KP-01", 4, null));
        assertEquals("AVAILABLE", wireStatus("GL-0001"));
        orderService.archive(id, new ArchiveRequest("exc-g-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    @Test
    void expiredTicketKeepsWireAppliedAndAllowsRemoveReturn() {
        long id = readyTicket("exp-g", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("exp-g-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("exp-g-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        groundWireService.apply("GL-0001", new ApplyRequest("exp-g-a", id, 1, "OP-01", "GD-01", 2, null));
        // 恰满24小时作废：挂接实物不得自动恢复可用
        clock.plus(Duration.ofHours(24));
        ApiException expired = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 1, new ConfirmStepRequest("exp-g-c2", "OP-01", "GD-01")));
        assertEquals("TICKET_EXPIRED", expired.code());
        assertEquals("EXPIRED", orderService.get(id).ticket().status());
        assertEquals("APPLIED", wireStatus("GL-0001"));
        // 仍须合法拆除与归还，之后允许归档
        groundWireService.remove("GL-0001", new RemoveRequest("exp-g-r", id, 1, "OP-01", "GD-01", 3, null));
        groundWireService.returnWire("GL-0001", new ReturnRequest("exp-g-t", "KP-01", 4, null));
        orderService.archive(id, new ArchiveRequest("exp-g-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentBorrowNoDoubleOccupancy() throws Exception {
        long ticketA = draft("cb-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draft("cb-b", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable borrowA = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.borrow(new BorrowRequest(
                        "cb-ra", ticketA, "INT-1101", null, List.of(item("GL-0001", 1))));
                success.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Runnable borrowB = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.borrow(new BorrowRequest(
                        "cb-rb", ticketB, "INT-1102", null, List.of(item("GL-0001", 1))));
                success.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(borrowA);
        Future<?> f2 = pool.submit(borrowB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, success.get(), "同一实物不得被两张票同时占用");
        assertEquals(1, rejected.get());
        assertEquals("BORROWED", wireStatus("GL-0001"));
        assertEquals(2, wireVersion("GL-0001"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_loan WHERE wire_id = "
                        + "(SELECT id FROM ground_wire WHERE wire_no = 'GL-0001')", Integer.class));
    }

    @Test
    void concurrentApplyAndEnergizeNeverBothPass() throws Exception {
        // 间隔停电（开关分位），票仅含送电步；接地线已借出待挂接
        jdbc.update("UPDATE device_state SET state = 'OPEN' WHERE device_id = 'BR-1101'");
        long id = readyTicket("race-g", "INT-1101", step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        groundWireService.borrow(new BorrowRequest("race-g-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applyOk = new AtomicInteger();
        AtomicInteger energizeOk = new AtomicInteger();
        Runnable apply = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.apply("GL-0001",
                        new ApplyRequest("race-g-a", id, 1, "OP-01", "GD-01", 2, null));
                applyOk.incrementAndGet();
            } catch (ApiException e) {
                // 送电先完成则带电挂接被拒绝
            }
        };
        Runnable energize = () -> {
            ready.countDown();
            await(start);
            try {
                orderService.confirm(id, 1, new ConfirmStepRequest("race-g-c", "OP-01", "GD-01"));
                energizeOk.incrementAndGet();
            } catch (ApiException e) {
                // 挂接先完成则送电门禁拒绝
            }
        };
        Future<?> f1 = pool.submit(apply);
        Future<?> f2 = pool.submit(energize);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, applyOk.get() + energizeOk.get(), "挂接与送电并发必须恰好一方成功");
        boolean wireApplied = "APPLIED".equals(wireStatus("GL-0001"));
        boolean stepDone = "DONE".equals(orderService.get(id).steps().get(0).status());
        assertTrue(!(stepDone && wireApplied), "不得既通过送电又留下挂接");
        assertEquals(applyOk.get() == 1, wireApplied);
        assertEquals(energizeOk.get() == 1, stepDone);
    }

    // ---------- 幂等边界（HTTP 层） ----------

    @Test
    void borrowIdempotentReplaySameKeySameParams() throws Exception {
        long id = readyTicket("i-b", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "i-b-1", "ticketId", id, "intervalId", "INT-1101",
                "wires", List.of(Map.of("wireNo", "GL-0001", "expectedVersion", 1))));
        MvcResult r1 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        assertEquals(2, wireVersion("GL-0001"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_loan", Integer.class));
    }

    @Test
    void borrowSameKeyDifferentParamsConflict() throws Exception {
        long id = readyTicket("i-x", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body1 = mapper.writeValueAsString(Map.of(
                "requestId", "i-x-1", "ticketId", id, "intervalId", "INT-1101",
                "wires", List.of(Map.of("wireNo", "GL-0001", "expectedVersion", 1))));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());
        String body2 = mapper.writeValueAsString(Map.of(
                "requestId", "i-x-1", "ticketId", id, "intervalId", "INT-1101",
                "wires", List.of(Map.of("wireNo", "GL-0002", "expectedVersion", 1))));
        MvcResult conflict = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(conflict.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH"));
        assertEquals("AVAILABLE", wireStatus("GL-0002"));
    }

    @Test
    void failedBorrowDoesNotOccupyIdempotencyKey() throws Exception {
        long ticketA = readyTicket("i-f-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(new BorrowRequest(
                "i-f-b0", ticketA, "INT-1101", null, List.of(item("GL-0001", 1))));
        long ticketB = draft("i-f-b", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        // 首次失败（GL-0001 已借出）：422 且不占键
        String failBody = mapper.writeValueAsString(Map.of(
                "requestId", "i-f-1", "ticketId", ticketB, "intervalId", "INT-1102",
                "wires", List.of(Map.of("wireNo", "GL-0001", "expectedVersion", 1))));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(failBody))
                .andExpect(status().isUnprocessableEntity());
        // 同键换合法参数：按首次请求正常执行
        String okBody = mapper.writeValueAsString(Map.of(
                "requestId", "i-f-1", "ticketId", ticketB, "intervalId", "INT-1102",
                "wires", List.of(Map.of("wireNo", "GL-0002", "expectedVersion", 1))));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isOk());
        assertEquals("BORROWED", wireStatus("GL-0002"));
    }

    @Test
    void applyIdempotentReplayExecutesOnce() throws Exception {
        long id = readyTicket("i-a", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("i-a-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("i-a-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "i-a-1", "ticketId", id, "stepSeq", 1,
                "operatorId", "OP-01", "guardianId", "GD-01", "expectedVersion", 2));
        mockMvc.perform(post("/api/ground-wires/GL-0001/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ground-wires/GL-0001/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals("APPLIED", wireStatus("GL-0001"));
        assertEquals(3, wireVersion("GL-0001"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_event", Integer.class));
    }

    @Test
    void expectedVersionIsPartOfFingerprint() throws Exception {
        long id = readyTicket("i-v", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("i-v-c", "OP-01", "GD-01"));
        groundWireService.borrow(new BorrowRequest("i-v-b", id, "INT-1101", null, List.of(item("GL-0001", 1))));
        String body1 = mapper.writeValueAsString(Map.of(
                "requestId", "i-v-1", "ticketId", id, "stepSeq", 1,
                "operatorId", "OP-01", "guardianId", "GD-01", "expectedVersion", 2));
        mockMvc.perform(post("/api/ground-wires/GL-0001/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());
        // 同键异版本：指纹不同，409
        String body2 = mapper.writeValueAsString(Map.of(
                "requestId", "i-v-1", "ticketId", id, "stepSeq", 1,
                "operatorId", "OP-01", "guardianId", "GD-01", "expectedVersion", 3));
        mockMvc.perform(post("/api/ground-wires/GL-0001/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }
}
