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
import java.util.concurrent.atomic.AtomicReference;

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
import com.example.starter.ground.GroundWireDtos.AttachRequest;
import com.example.starter.ground.GroundWireDtos.BatchView;
import com.example.starter.ground.GroundWireDtos.BorrowRequest;
import com.example.starter.ground.GroundWireDtos.DetachRequest;
import com.example.starter.ground.GroundWireDtos.ReturnRequest;
import com.example.starter.ground.GroundWireDtos.TicketGroundView;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireItem;
import com.example.starter.ground.GroundWireDtos.WireView;
import com.example.starter.ground.GroundWireService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 临时接地线借用核销与送电门禁集成测试：真实 H2（MODE=MySQL）验证状态机、
 * 双人确认证据、乐观版本、送电/归档门禁、事务与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class GroundWireIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant OP_TIME = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private SwitchingOrderService orderService;
    @Autowired
    private GroundWireService groundService;
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
        jdbc.update("DELETE FROM ground_wire_event");
        jdbc.update("DELETE FROM ground_wire");
        jdbc.update("DELETE FROM switching_step");
        jdbc.update("DELETE FROM switching_ticket");
        jdbc.update("DELETE FROM interlock_hit");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM interval_execution_lock");
        jdbc.update("DELETE FROM telemetry");
        jdbc.update("DELETE FROM unlock_record");
        jdbc.update("INSERT INTO ground_wire (wire_no, status, version, created_at, updated_at) VALUES"
                + " ('TW-0001','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('TW-0002','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('TW-0003','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('TW-0004','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
        jdbc.update("UPDATE device_state SET state = 'CLOSED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id IN ('BR-1101','DS-1101','BR-F1','BR-F2')");
        jdbc.update("UPDATE device_state SET state = 'REMOVED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id = 'GW-1101'");
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

    private static WireItem item(String wireNo, int version) {
        return new WireItem(wireNo, version);
    }

    private BatchView borrow(long ticketId, String reqId, WireItem... items) {
        return groundService.borrow(new BorrowRequest(reqId, ticketId, List.of(items), OP_TIME));
    }

    private WireView attach(long ticketId, String wireNo, int stepSeq, int version) {
        return groundService.attach(new AttachRequest(
                "att-" + wireNo + "-" + version, ticketId, wireNo, stepSeq, "OP-01", "GD-01", version, OP_TIME));
    }

    private WireView detach(long ticketId, String wireNo, int version) {
        return groundService.detach(new DetachRequest(
                "det-" + wireNo + "-" + version, ticketId, wireNo, "OP-01", "GD-01", version, OP_TIME));
    }

    private BatchView returnWires(long ticketId, String reqId, WireItem... items) {
        return groundService.returnWires(new ReturnRequest(reqId, ticketId, "KP-01", List.of(items), OP_TIME));
    }

    private String wireStatus(String wireNo) {
        return jdbc.queryForObject("SELECT status FROM ground_wire WHERE wire_no = ?", String.class, wireNo);
    }

    private int wireVersion(String wireNo) {
        return jdbc.queryForObject("SELECT version FROM ground_wire WHERE wire_no = ?", Integer.class, wireNo);
    }

    // ---------- 主流程：借用→挂接→拆除→归还核销→归档 ----------

    @Test
    void borrowAttachDetachReturnMainFlow() {
        long id = readyTicket("gw-main", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-main-c1", "OP-01", "GD-01"));

        BatchView borrowed = borrow(id, "gw-b1", item("TW-0001", 1));
        assertEquals("BORROWED", borrowed.wires().get(0).status());
        assertEquals(2, borrowed.wires().get(0).version());
        assertEquals(id, borrowed.wires().get(0).holderTicketId());
        assertEquals("INT-1101", borrowed.wires().get(0).intervalId());

        WireView attached = attach(id, "TW-0001", 1, 2);
        assertEquals("ATTACHED", attached.status());
        assertEquals(3, attached.version());

        WireView detached = detach(id, "TW-0001", 3);
        assertEquals("DETACHED", detached.status());
        assertEquals(4, detached.version());

        BatchView returned = returnWires(id, "gw-r1", item("TW-0001", 4));
        assertEquals("AVAILABLE", returned.wires().get(0).status());
        assertEquals(5, returned.wires().get(0).version());
        assertNull(returned.wires().get(0).holderTicketId());
        assertNull(returned.wires().get(0).intervalId());

        // 不可覆盖证据：四个动作全程留痕，挂接引用本票步序与双人
        WireDetailView detail = groundService.wireDetail("TW-0001");
        assertEquals(List.of("BORROW", "ATTACH", "DETACH", "RETURN"),
                detail.events().stream().map(e -> e.action()).toList());
        assertEquals(1, detail.events().get(1).stepSeq());
        assertEquals("OP-01", detail.events().get(1).operatorId());
        assertEquals("GD-01", detail.events().get(1).guardianId());
        assertEquals("OP-01", detail.events().get(2).operatorId());
        assertEquals("GD-01", detail.events().get(2).guardianId());
        assertEquals("KP-01", detail.events().get(3).keeperId());
        assertEquals(OP_TIME, detail.events().get(0).occurredAt());

        // 全部归还后允许归档，历史证据保留
        orderService.archive(id, new ArchiveRequest("gw-main-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
        TicketGroundView history = groundService.ticketGround(id);
        assertTrue(history.heldWires().isEmpty());
        assertEquals(4, history.events().size());
    }

    // ---------- 批量借用：一次多条，任一不可用整批拒绝 ----------

    @Test
    void borrowBatchSucceedsAndRejectsAtomically() {
        long ticketA = readyTicket("gw-ba", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        BatchView batch = borrow(ticketA, "gw-b-batch", item("TW-0001", 1), item("TW-0002", 1));
        assertEquals(2, batch.wires().size());
        assertEquals("BORROWED", wireStatus("TW-0001"));
        assertEquals("BORROWED", wireStatus("TW-0002"));

        long ticketB = draft("gw-bb", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        // TW-0001 已被票A占用：整批拒绝，空闲的 TW-0003 不得被票B连带借出
        ApiException ex = assertThrows(ApiException.class, () ->
                borrow(ticketB, "gw-b-batch2", item("TW-0001", 2), item("TW-0003", 1)));
        assertEquals("WIRE_NOT_HELD", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("AVAILABLE", wireStatus("TW-0003"));
        assertEquals(1, wireVersion("TW-0003"));
        Integer eventsB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_event WHERE ticket_id = ?", Integer.class, ticketB);
        assertEquals(0, eventsB);
    }

    // ---------- 借用绑定：未归档且未作废；到期票先作废再拒绝 ----------

    @Test
    void borrowRejectsExpiredTicket() {
        long id = readyTicket("gw-exp", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        clock.plus(Duration.ofHours(24));
        ApiException ex = assertThrows(ApiException.class, () -> borrow(id, "gw-b-exp", item("TW-0001", 1)));
        assertEquals("TICKET_STATE", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("EXPIRED", orderService.get(id).ticket().status());
        assertEquals("AVAILABLE", wireStatus("TW-0001"));
    }

    @Test
    void borrowVersionConflictAndUnknownWire() {
        long id = readyTicket("gw-ver", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException conflict = assertThrows(ApiException.class, () -> borrow(id, "gw-b-v", item("TW-0001", 5)));
        assertEquals("VERSION_CONFLICT", conflict.code());
        assertEquals(409, conflict.status().value());
        ApiException notFound = assertThrows(ApiException.class, () -> borrow(id, "gw-b-nf", item("TW-9999", 1)));
        assertEquals("WIRE_NOT_FOUND", notFound.code());
        assertEquals(404, notFound.status().value());
    }

    // ---------- 挂接：双人确认、引用本票步骤、带电拒绝 ----------

    @Test
    void attachRequiresTwoDistinctPeople() {
        long id = readyTicket("gw-dbl", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-dbl-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-dbl", item("TW-0001", 1));
        ApiException ex = assertThrows(ApiException.class, () -> groundService.attach(new AttachRequest(
                "gw-a-dbl", id, "TW-0001", 1, "OP-01", "OP-01", 2, OP_TIME)));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", ex.code());
        assertEquals(400, ex.status().value());
        assertEquals("BORROWED", wireStatus("TW-0001"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_event WHERE action = 'ATTACH'",
                Integer.class));
    }

    @Test
    void attachRejectsStepNotOfThisTicket() {
        long id = readyTicket("gw-step", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        borrow(id, "gw-b-step", item("TW-0001", 1));
        ApiException ex = assertThrows(ApiException.class, () -> attach(id, "TW-0001", 9, 2));
        assertEquals("STEP_NOT_FOUND", ex.code());
        assertEquals(404, ex.status().value());
        assertEquals("BORROWED", wireStatus("TW-0001"));
    }

    @Test
    void attachRejectsLiveInterval() {
        long id = readyTicket("gw-live", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        // 开关仍合位（间隔带电）：挂接拒绝
        borrow(id, "gw-b-live", item("TW-0001", 1));
        ApiException ex = assertThrows(ApiException.class, () -> attach(id, "TW-0001", 1, 2));
        assertEquals("GROUND_LIVE_INTERVAL", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("BORROWED", wireStatus("TW-0001"));
    }

    @Test
    void attachVersionConflict() {
        long id = readyTicket("gw-av", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-av-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-av", item("TW-0001", 1));
        ApiException ex = assertThrows(ApiException.class, () -> attach(id, "TW-0001", 1, 1));
        assertEquals("VERSION_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());
    }

    // ---------- 拆除：双人确认、非持票方拒绝 ----------

    @Test
    void detachRequiresTwoPeopleAndHolderTicket() {
        long id = readyTicket("gw-det", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-det-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-det", item("TW-0001", 1));
        attach(id, "TW-0001", 1, 2);
        ApiException single = assertThrows(ApiException.class, () -> groundService.detach(new DetachRequest(
                "gw-d-single", id, "TW-0001", "OP-01", "OP-01", 3, OP_TIME)));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", single.code());
        assertEquals(400, single.status().value());

        long other = draft("gw-det-other", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));
        ApiException notHeld = assertThrows(ApiException.class, () -> groundService.detach(new DetachRequest(
                "gw-d-other", other, "TW-0001", "OP-02", "GD-02", 3, OP_TIME)));
        assertEquals("WIRE_NOT_HELD", notHeld.code());
        assertEquals(422, notHeld.status().value());
        assertEquals("ATTACHED", wireStatus("TW-0001"));
    }

    // ---------- 送电门禁：事务内重查，仍挂接拒绝且不推进步序 ----------

    @Test
    void energizeStepRechecksAttachedWiresInTransaction() {
        long id = readyTicket("gw-gate", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-gate-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-gate", item("TW-0001", 1));
        attach(id, "TW-0001", 1, 2);

        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 2, new ConfirmStepRequest("gw-gate-c2", "OP-01", "GD-01")));
        assertEquals("GROUND_WIRE_ATTACHED", ex.code());
        assertEquals(422, ex.status().value());
        // 不推进步序，闭锁命中留痕
        assertEquals("PENDING", orderService.get(id).steps().get(1).status());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? AND step_seq = 2", String.class, id));

        // 合法拆除后送电放行
        detach(id, "TW-0001", 3);
        var view = orderService.confirm(id, 2, new ConfirmStepRequest("gw-gate-c3", "OP-01", "GD-01"));
        assertEquals("DONE", view.stepStatus());
    }

    // ---------- 票作废不自动释放实物，仍须合法拆除与归还 ----------

    @Test
    void expiryDoesNotFreeAttachedWireAndDetachReturnStillAllowed() {
        long id = readyTicket("gw-exp2", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-exp2-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-exp2", item("TW-0001", 1));
        attach(id, "TW-0001", 1, 2);

        clock.plus(Duration.ofHours(24));
        ApiException expired = assertThrows(ApiException.class, () ->
                orderService.confirm(id, 2, new ConfirmStepRequest("gw-exp2-c2", "OP-01", "GD-01")));
        assertEquals("TICKET_EXPIRED", expired.code());
        // 作废不自动把挂接实物变回可用
        assertEquals("ATTACHED", wireStatus("TW-0001"));
        assertEquals(id, jdbc.queryForObject(
                "SELECT holder_ticket_id FROM ground_wire WHERE wire_no = 'TW-0001'", Long.class));

        // 仍须合法拆除与归还，之后方可归档
        detach(id, "TW-0001", 3);
        returnWires(id, "gw-r-exp2", item("TW-0001", 4));
        assertEquals("AVAILABLE", wireStatus("TW-0001"));
        orderService.archive(id, new ArchiveRequest("gw-exp2-arc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 归档门禁：本票借用全部归还方可归档 ----------

    @Test
    void archiveRequiresAllBorrowedWiresReturned() {
        long id = readyTicket("gw-arc", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-arc-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-arc", item("TW-0001", 1));

        ApiException ex = assertThrows(ApiException.class, () ->
                orderService.archive(id, new ArchiveRequest("gw-arc-1")));
        assertEquals("GROUND_WIRE_NOT_RETURNED", ex.code());
        assertEquals(422, ex.status().value());
        assertNotEquals("ARCHIVED", orderService.get(id).ticket().status());

        attach(id, "TW-0001", 1, 2);
        detach(id, "TW-0001", 3);
        returnWires(id, "gw-r-arc", item("TW-0001", 4));
        orderService.archive(id, new ArchiveRequest("gw-arc-2"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 归还核销：只核销已拆除实物 ----------

    @Test
    void returnRejectsAttachedOrBorrowedOnlyWires() {
        long id = readyTicket("gw-ret", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-ret-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-ret", item("TW-0001", 1), item("TW-0002", 1));
        attach(id, "TW-0001", 1, 2);

        // 已挂接不得核销
        ApiException attached = assertThrows(ApiException.class, () ->
                returnWires(id, "gw-r-1", item("TW-0001", 3)));
        assertEquals("WIRE_STATE", attached.code());
        assertEquals(422, attached.status().value());
        // 仅借出未拆除不得核销
        ApiException borrowedOnly = assertThrows(ApiException.class, () ->
                returnWires(id, "gw-r-2", item("TW-0002", 2)));
        assertEquals("WIRE_STATE", borrowedOnly.code());
        assertEquals("ATTACHED", wireStatus("TW-0001"));
        assertEquals("BORROWED", wireStatus("TW-0002"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_event WHERE action = 'RETURN'", Integer.class));
    }

    // ---------- 并发：竞争票借用同一实物不能双占 ----------

    @Test
    void concurrentBorrowSameWireOnlyOneSucceeds() throws Exception {
        long ticketA = draft("gw-ca", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draft("gw-cb", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable taskA = () -> {
            ready.countDown();
            await(start);
            try {
                borrow(ticketA, "gw-b-ca", item("TW-0001", 1));
                success.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Runnable taskB = () -> {
            ready.countDown();
            await(start);
            try {
                borrow(ticketB, "gw-b-cb", item("TW-0001", 1));
                success.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(taskA);
        Future<?> f2 = pool.submit(taskB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, success.get(), "同一实物最多被一张票占用");
        assertEquals(1, rejected.get());
        assertEquals(2, wireVersion("TW-0001"));
        assertEquals("BORROWED", wireStatus("TW-0001"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_event WHERE wire_no = 'TW-0001' AND action = 'BORROW'",
                Integer.class));
    }

    // ---------- 并发：挂接与送电不得既通过送电又留下挂接 ----------

    @Test
    void concurrentAttachAndEnergizeNotBothSucceed() throws Exception {
        long id = readyTicket("gw-race", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-race-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-race", item("TW-0001", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger attachOk = new AtomicInteger();
        AtomicInteger confirmOk = new AtomicInteger();
        AtomicReference<ApiException> attachErr = new AtomicReference<>();
        AtomicReference<ApiException> confirmErr = new AtomicReference<>();
        Runnable doAttach = () -> {
            ready.countDown();
            await(start);
            try {
                attach(id, "TW-0001", 1, 2);
                attachOk.incrementAndGet();
            } catch (ApiException e) {
                attachErr.set(e);
            }
        };
        Runnable doEnergize = () -> {
            ready.countDown();
            await(start);
            try {
                orderService.confirm(id, 2, new ConfirmStepRequest("gw-race-c2", "OP-01", "GD-01"));
                confirmOk.incrementAndGet();
            } catch (ApiException e) {
                confirmErr.set(e);
            }
        };
        Future<?> f1 = pool.submit(doAttach);
        Future<?> f2 = pool.submit(doEnergize);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, attachOk.get() + confirmOk.get(), "挂接与送电不得双通");
        String stepStatus = orderService.get(id).steps().get(1).status();
        String wire = wireStatus("TW-0001");
        if (confirmOk.get() == 1) {
            assertEquals("DONE", stepStatus);
            assertEquals("BORROWED", wire, "送电通过后不得留下挂接");
            assertEquals("GROUND_LIVE_INTERVAL", attachErr.get().code());
        } else {
            assertEquals("ATTACHED", wire);
            assertEquals("PENDING", stepStatus, "挂接仍在时送电不得推进");
            assertEquals("GROUND_WIRE_ATTACHED", confirmErr.get().code());
        }
    }

    // ---------- 并发：归还与竞争票借用不双占 ----------

    @Test
    void concurrentReturnAndBorrowNeverDoubleOccupy() throws Exception {
        long ticketA = readyTicket("gw-ra", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(ticketA, 1, new ConfirmStepRequest("gw-ra-c1", "OP-01", "GD-01"));
        borrow(ticketA, "gw-b-ra", item("TW-0001", 1));
        attach(ticketA, "TW-0001", 1, 2);
        detach(ticketA, "TW-0001", 3);
        long ticketB = draft("gw-rb", "INT-1102", step("OPEN_BREAKER", "BR-X", "OPEN"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger returnOk = new AtomicInteger();
        AtomicInteger borrowOk = new AtomicInteger();
        Runnable doReturn = () -> {
            ready.countDown();
            await(start);
            try {
                returnWires(ticketA, "gw-r-race", item("TW-0001", 4));
                returnOk.incrementAndGet();
            } catch (ApiException ignored) {
                // 归还失败则实物仍归票A，不构成双占
            }
        };
        Runnable doBorrow = () -> {
            ready.countDown();
            await(start);
            try {
                borrow(ticketB, "gw-b-race", item("TW-0001", 5));
                borrowOk.incrementAndGet();
            } catch (ApiException ignored) {
                // 借用失败则实物未占用，不构成双占
            }
        };
        Future<?> f1 = pool.submit(doReturn);
        Future<?> f2 = pool.submit(doBorrow);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, returnOk.get(), "归还必须成功");
        Long holder = jdbc.queryForObject(
                "SELECT holder_ticket_id FROM ground_wire WHERE wire_no = 'TW-0001'", Long.class);
        if (borrowOk.get() == 1) {
            // 借用仅在归还提交后成功：占有人唯一且为票B
            assertEquals(ticketB, holder);
            assertEquals(6, wireVersion("TW-0001"));
        } else {
            assertNull(holder, "归还后无人占用");
            assertEquals(5, wireVersion("TW-0001"));
        }
    }

    // ---------- 幂等：同键同参重放、异参409、失败不占键 ----------

    @Test
    void borrowIdempotentReplayAndMismatch() throws Exception {
        long id = readyTicket("gw-idem", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "gw-idem-1",
                "ticketId", id,
                "items", List.of(Map.of("wireNo", "TW-0001", "expectedVersion", 1)),
                "occurredAt", "2026-09-26T01:00:00Z"));
        MvcResult r1 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        assertEquals(2, wireVersion("TW-0001"), "重放不得重复借用");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_event WHERE action = 'BORROW'", Integer.class));

        // 同键异参（精确时刻不同）→ 409
        String tampered = mapper.writeValueAsString(Map.of(
                "requestId", "gw-idem-1",
                "ticketId", id,
                "items", List.of(Map.of("wireNo", "TW-0001", "expectedVersion", 1)),
                "occurredAt", "2026-09-26T02:00:00Z"));
        MvcResult conflict = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(conflict.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH"));
    }

    @Test
    void failedBorrowDoesNotOccupyIdempotencyKey() throws Exception {
        long id = readyTicket("gw-idem2", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String badBody = mapper.writeValueAsString(Map.of(
                "requestId", "gw-idem-2",
                "ticketId", id,
                "items", List.of(Map.of("wireNo", "TW-9999", "expectedVersion", 1)),
                "occurredAt", "2026-09-26T01:00:00Z"));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isNotFound());
        // 失败不占键：同键换合法参数可正常执行
        String goodBody = mapper.writeValueAsString(Map.of(
                "requestId", "gw-idem-2",
                "ticketId", id,
                "items", List.of(Map.of("wireNo", "TW-0003", "expectedVersion", 1)),
                "occurredAt", "2026-09-26T01:00:00Z"));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk());
        assertEquals("BORROWED", wireStatus("TW-0003"));
    }

    // ---------- 查询接口 ----------

    @Test
    void queryEndpointsReturnLedgerAndEvidence() throws Exception {
        long id = readyTicket("gw-q", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.confirm(id, 1, new ConfirmStepRequest("gw-q-c1", "OP-01", "GD-01"));
        borrow(id, "gw-b-q", item("TW-0001", 1));
        attach(id, "TW-0001", 1, 2);

        mockMvc.perform(get("/api/ground-wires")).andExpect(status().isOk());
        MvcResult detail = mockMvc.perform(get("/api/ground-wires/TW-0001"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(detail.getResponse().getContentAsString().contains("ATTACHED"));
        MvcResult byTicket = mockMvc.perform(get("/api/ground-wires/ticket/" + id))
                .andExpect(status().isOk()).andReturn();
        assertTrue(byTicket.getResponse().getContentAsString().contains("TW-0001"));
        mockMvc.perform(get("/api/ground-wires/TW-9999")).andExpect(status().isNotFound());
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
