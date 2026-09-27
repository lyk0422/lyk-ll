package com.example.starter.ground;

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
import com.example.starter.ground.GroundWireDtos.AttachWireRequest;
import com.example.starter.ground.GroundWireDtos.BorrowWiresRequest;
import com.example.starter.ground.GroundWireDtos.DetachWireRequest;
import com.example.starter.ground.GroundWireDtos.ReturnWiresRequest;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireStepResult;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;
import com.example.starter.ticket.TicketDtos.TicketDetailView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实物接地线实物联锁集成测试：真实 H2（MODE=MySQL）验证占用约束、不可覆盖证据、
 * 送电事务内复查、归档归还要求、并发不双占及 requestId 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class GroundWireIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private GroundWireService groundService;
    @Autowired
    private SwitchingOrderService orderService;
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
        jdbc.update("DELETE FROM ground_wire_evidence");
        jdbc.update("DELETE FROM ground_wire_loan");
        jdbc.update("DELETE FROM switching_step");
        jdbc.update("DELETE FROM switching_ticket");
        jdbc.update("DELETE FROM interlock_hit");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM interval_execution_lock");
        jdbc.update("DELETE FROM telemetry");
        jdbc.update("UPDATE ground_wire SET status = 'AVAILABLE', interval_id = NULL,"
                + " occupied_by_ticket = NULL, version = 1, updated_at = TIMESTAMP '2026-01-01 00:00:00'");
        jdbc.update("UPDATE device_state SET state = 'CLOSED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id IN ('BR-1101','DS-1101','BR-F1','BR-F2')");
        jdbc.update("UPDATE device_state SET state = 'REMOVED', updated_at = TIMESTAMP '2026-01-01 00:00:00'"
                + " WHERE device_id = 'GW-1101'");
        clock.setInstant(T0);
    }

    // ---------- 辅助 ----------

    private long fullTicket(String reqPrefix) {
        return fullTicket(reqPrefix, "INT-1101");
    }

    private long fullTicket(String reqPrefix, String intervalId) {
        long id = orderService.create(new CreateTicketRequest(
                "c-" + reqPrefix, intervalId, "接地线全流程票", List.of(
                        new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"),
                        new StepSpec("APPLY_GROUND", "GW-1101", "APPLIED"),
                        new StepSpec("REMOVE_GROUND", "GW-1101", "REMOVED"),
                        new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-" + reqPrefix, "REV-01"));
        orderService.issue(id, new IssueRequest("iss-" + reqPrefix));
        return id;
    }

    private long draftTicket(String reqPrefix, String intervalId, StepSpec... steps) {
        return orderService.create(new CreateTicketRequest(
                "c-" + reqPrefix, intervalId, "草稿票", List.of(steps))).ticket().ticketId();
    }

    private void confirm(long id, int seq, String reqId) {
        orderService.confirm(id, seq, new ConfirmStepRequest(reqId, "OP-01", "GD-01"));
    }

    private void borrow(long ticketId, String reqId, long version, String... codes) {
        groundService.borrow(ticketId, new BorrowWiresRequest(reqId, ticketId, version, List.of(codes), T1));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---------- 主流程：借用→挂接→（送电拦截）→拆除→归还→归档 ----------

    @Test
    void fullFlowBorrowAttachDetachReturnArchiveKeepsHistory() {
        long id = fullTicket("full");
        confirm(id, 1, "full-c1");
        groundService.borrow(id, new BorrowWiresRequest("b-full", id, 1L, List.of("JDX-1001"), T1));
        assertEquals("BORROWED", groundService.getWire("JDX-1001").wire().status());
        assertEquals(2, groundService.getWire("JDX-1001").wire().version());

        WireStepResult attached = groundService.attach(id, new AttachWireRequest(
                "a-full", id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        assertEquals("APPLIED", attached.wireStatus());
        assertEquals(3, attached.version());

        // 挂接期间送电类步必须被拦（见专项用例），先完成本票挂/拆步与拆除
        confirm(id, 2, "full-c2");
        confirm(id, 3, "full-c3");
        WireStepResult detached = groundService.detach(id, new DetachWireRequest(
                "d-full", id, 3L, 3, "JDX-1001", "OP-01", "GD-01", T1));
        assertEquals("REMOVED_PENDING_RETURN", detached.wireStatus());
        assertEquals(4, detached.version());

        // 已拆除待归还状态下送电复查通过，步序推进
        confirm(id, 4, "full-c4");
        assertEquals("DONE", orderService.get(id).steps().get(3).status());

        // 未归还前归档拒绝
        ApiException block = assertThrows(ApiException.class,
                () -> orderService.archive(id, new ArchiveRequest("arc-block")));
        assertEquals("GROUND_WIRES_NOT_RETURNED", block.code());
        assertEquals(422, block.status().value());

        // 仓管核对编号后归还，版本再加1，实物回到可用
        groundService.returnWires(id, new ReturnWiresRequest(
                "r-full", id, 4L, List.of("JDX-1001"), "KEEPER-01", T1));
        WireDetailView wire = groundService.getWire("JDX-1001");
        assertEquals("AVAILABLE", wire.wire().status());
        assertNull(wire.wire().occupiedByTicket());
        assertEquals(5, wire.wire().version());

        orderService.archive(id, new ArchiveRequest("arc-full"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());

        // 历史借用记录与挂/拆证据归档后保留
        assertEquals("RETURNED", groundService.getWire("JDX-1001").loans().get(0).status());
        assertEquals("KEEPER-01", groundService.getWire("JDX-1001").loans().get(0).keeperId());
        List<String> actions = groundService.ticketGround(id).evidences().stream().map(e -> e.action()).toList();
        assertEquals(List.of("APPLIED", "REMOVED"), actions);
    }

    // ---------- 实物联锁：挂着线确认送电被拦，步序不前进，且不信任拟票快照 ----------

    @Test
    void energizeBlockedWhilePhysicalWireAppliedEvenWhenSnapshotSaysRemoved() {
        long id = fullTicket("block");
        confirm(id, 1, "block-c1");
        borrow(id, "b-block", 1L, "JDX-1001");
        groundService.attach(id, new AttachWireRequest(
                "a-block", id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        confirm(id, 2, "block-c2");
        // 先确认拆线步：遥信快照把 GW-1101 置为已拆除，但实物未拆（陈旧快照场景）
        confirm(id, 3, "block-c3");
        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'GW-1101'", String.class));
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());

        // 只信快照会放行送电；实物台账复查必须拦住，且步序不推进
        ApiException physical = assertThrows(ApiException.class, () -> confirm(id, 4, "block-c4"));
        assertEquals("GROUND_WIRE_STILL_APPLIED", physical.code());
        assertEquals(422, physical.status().value());
        assertEquals("PENDING", orderService.get(id).steps().get(3).status());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class, id));
    }

    @Test
    void energizeConfirmRejectedAndStepNotAdvancedWithAppliedWire() {
        // 票：断开关→挂地线→合开关（送电步紧跟挂线步）
        long id = orderService.create(new CreateTicketRequest(
                "c-eng", "INT-1101", "挂线送电票", List.of(
                        new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"),
                        new StepSpec("APPLY_GROUND", "GW-1101", "APPLIED"),
                        new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-eng", "REV-01"));
        orderService.issue(id, new IssueRequest("iss-eng"));
        confirm(id, 1, "eng-c1");
        borrow(id, "b-eng", 1L, "JDX-1002");
        groundService.attach(id, new AttachWireRequest(
                "a-eng", id, 2L, 2, "JDX-1002", "OP-01", "GD-01", T1));
        confirm(id, 2, "eng-c2");
        // 拟票/遥信快照显示接地线已拆除（陈旧快照），实物台账仍挂接
        jdbc.update("UPDATE device_state SET state = 'REMOVED' WHERE device_id = 'GW-1101'");

        // 只信快照会放行送电；实物台账复查必须拦住，步序停在 PENDING，票不前进
        ApiException ex = assertThrows(ApiException.class, () -> confirm(id, 3, "eng-c3"));
        assertEquals("GROUND_WIRE_STILL_APPLIED", ex.code());
        assertEquals(422, ex.status().value());
        TicketDetailView detail = orderService.get(id);
        assertEquals("PENDING", detail.steps().get(2).status());
        assertEquals("EXECUTING", detail.ticket().status());
        // 开关维持分位，未被送电
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        // 闭锁命中留痕
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class, id));
    }

    // ---------- 批量借用：任一不可用整批拒绝 ----------

    @Test
    void batchBorrowWholeBatchRejectedIfAnyUnavailable() {
        long ticketA = draftTicket("a", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        borrow(ticketA, "b-a", 1L, "JDX-1001");

        long ticketB = draftTicket("b", "INT-1102", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class,
                () -> borrow(ticketB, "b-b", 1L, "JDX-1001", "JDX-1002"));
        assertEquals("WIRE_NOT_AVAILABLE", ex.code());
        assertEquals(422, ex.status().value());
        // 整批回滚：JDX-1002 未被占用，无借用记录
        assertEquals("AVAILABLE", groundService.getWire("JDX-1002").wire().status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_loan WHERE ticket_id = ?", Integer.class, ticketB));
    }

    @Test
    void samePhysicalWireCannotBeOccupiedByTwoTickets() {
        long ticketA = draftTicket("occ-a", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draftTicket("occ-b", "INT-1102", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        borrow(ticketA, "b-occ-a", 1L, "JDX-1003");
        ApiException ex = assertThrows(ApiException.class, () -> borrow(ticketB, "b-occ-b", 1L, "JDX-1003"));
        assertEquals("WIRE_NOT_AVAILABLE", ex.code());
        assertEquals(ticketA, groundService.getWire("JDX-1003").wire().occupiedByTicket());
    }

    @Test
    void borrowRejectsUnknownWireUnknownTicketWrongVersionAndVoidTicket() {
        long ticket = draftTicket("chk", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException notFound = assertThrows(ApiException.class,
                () -> borrow(ticket, "b-x1", 1L, "JDX-9999"));
        assertEquals("WIRE_NOT_FOUND", notFound.code());
        assertEquals(404, notFound.status().value());

        ApiException noTicket = assertThrows(ApiException.class, () ->
                groundService.borrow(999999L, new BorrowWiresRequest("b-x2", 999999L, 1L, List.of("JDX-1001"), T1)));
        assertEquals("TICKET_NOT_FOUND", noTicket.code());
        assertEquals(404, noTicket.status().value());

        ApiException version = assertThrows(ApiException.class,
                () -> borrow(ticket, "b-x3", 9L, "JDX-1001"));
        assertEquals("VERSION_MISMATCH", version.code());
        assertEquals(422, version.status().value());

        // 票已归档（终态）后不可再借用
        jdbc.update("UPDATE switching_ticket SET status = 'ARCHIVED' WHERE id = ?", ticket);
        ApiException archived = assertThrows(ApiException.class,
                () -> borrow(ticket, "b-x4", 1L, "JDX-1001"));
        assertEquals("TICKET_ARCHIVED", archived.code());
    }

    @Test
    void duplicateWireCodeInBatchIsBadRequest() {
        long ticket = draftTicket("dup", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException ex = assertThrows(ApiException.class,
                () -> borrow(ticket, "b-dup", 1L, "JDX-1001", "JDX-1001"));
        assertEquals("DUPLICATE_WIRE_CODE", ex.code());
        assertEquals(400, ex.status().value());
    }

    // ---------- 挂接/拆除：双人确认、步骤类型、证据不可覆盖 ----------

    @Test
    void attachAndDetachRequireTwoDifferentPersons() {
        long id = fullTicket("dc");
        confirm(id, 1, "dc-c1");
        borrow(id, "b-dc", 1L, "JDX-1001");
        ApiException attach = assertThrows(ApiException.class, () -> groundService.attach(id,
                new AttachWireRequest("a-dc", id, 2L, 2, "JDX-1001", "OP-01", "OP-01", T1)));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", attach.code());
        assertEquals(400, attach.status().value());
        assertEquals("BORROWED", groundService.getWire("JDX-1001").wire().status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_evidence", Integer.class));
    }

    @Test
    void attachMustReferenceApplyGroundStepOfThisTicket() {
        long id = fullTicket("st");
        confirm(id, 1, "st-c1");
        borrow(id, "b-st", 1L, "JDX-1001");
        // 引用拆除步挂接 → 拒绝
        ApiException wrongType = assertThrows(ApiException.class, () -> groundService.attach(id,
                new AttachWireRequest("a-st", id, 2L, 3, "JDX-1001", "OP-01", "GD-01", T1)));
        assertEquals("STEP_TYPE_MISMATCH", wrongType.code());
        // 引用不存在步 → 404
        ApiException noStep = assertThrows(ApiException.class, () -> groundService.attach(id,
                new AttachWireRequest("a-st2", id, 2L, 99, "JDX-1001", "OP-01", "GD-01", T1)));
        assertEquals("STEP_NOT_FOUND", noStep.code());
        assertEquals(404, noStep.status().value());
    }

    @Test
    void cannotAttachWireBorrowedByAnotherTicketOrReattachApplied() {
        long idA = fullTicket("ow-a");
        long idB = fullTicket("ow-b", "INT-1102");
        confirm(idA, 1, "owa-c1");
        confirm(idB, 1, "owb-c1");
        borrow(idA, "b-owa", 1L, "JDX-1001");
        // B 票挂 A 票借用实物
        ApiException ex = assertThrows(ApiException.class, () -> groundService.attach(idB,
                new AttachWireRequest("a-owb", idB, 2L, 2, "JDX-1001", "OP-02", "GD-02", T1)));
        assertEquals("WIRE_NOT_BORROWED", ex.code());

        groundService.attach(idA, new AttachWireRequest(
                "a-owa", idA, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        // 重复挂接：以旧版本号再次确认，乐观版本拒绝
        ApiException again = assertThrows(ApiException.class, () -> groundService.attach(idA,
                new AttachWireRequest("a-owa2", idA, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1)));
        assertEquals("VERSION_MISMATCH", again.code());
    }

    // ---------- 归还：只核销已拆除实物，仓管核对 ----------

    @Test
    void returnRejectsBorrowedNotDetachedAndForeignTicket() {
        long id = fullTicket("ret");
        long other = draftTicket("ret-other", "INT-1102", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        confirm(id, 1, "ret-c1");
        borrow(id, "b-ret", 1L, "JDX-1001");
        // 仅借出未挂拆，归还拒绝
        ApiException notDetached = assertThrows(ApiException.class, () -> groundService.returnWires(id,
                new ReturnWiresRequest("r-ret1", id, 2L, List.of("JDX-1001"), "KEEPER-01", T1)));
        assertEquals("WIRE_NOT_DETACHED", notDetached.code());
        // 非借用票归还
        ApiException foreign = assertThrows(ApiException.class, () -> groundService.returnWires(other,
                new ReturnWiresRequest("r-ret2", other, 2L, List.of("JDX-1001"), "KEEPER-01", T1)));
        assertEquals("WIRE_NOT_BORROWED_BY_TICKET", foreign.code());
        assertEquals("BORROWED", groundService.getWire("JDX-1001").wire().status());
    }

    // ---------- 票异常/作废不自动恢复实物，仍须合法拆除归还 ----------

    @Test
    void exceptionTicketKeepsWireAppliedUntilLawfulDetachAndReturn() {
        long id = fullTicket("exc");
        confirm(id, 1, "exc-c1");
        borrow(id, "b-exc", 1L, "JDX-1001");
        groundService.attach(id, new AttachWireRequest(
                "a-exc", id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        confirm(id, 2, "exc-c2");
        // 票转异常（模拟遥信不符），实物仍挂接、占用不释放
        jdbc.update("UPDATE switching_ticket SET status = 'EXCEPTION' WHERE id = ?", id);
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());
        assertEquals(id, groundService.getWire("JDX-1001").wire().occupiedByTicket());

        // 异常票仍可合法拆除（引用 REMOVE_GROUND 步，双人确认）
        WireStepResult detached = groundService.detach(id, new DetachWireRequest(
                "d-exc", id, 3L, 3, "JDX-1001", "OP-01", "GD-01", T1));
        assertEquals("REMOVED_PENDING_RETURN", detached.wireStatus());
        // 未归还前归档仍拒绝
        assertThrows(ApiException.class, () -> orderService.archive(id, new ArchiveRequest("arc-exc1")));
        groundService.returnWires(id, new ReturnWiresRequest(
                "r-exc", id, 4L, List.of("JDX-1001"), "KEEPER-01", T1));
        orderService.archive(id, new ArchiveRequest("arc-exc2"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
        assertEquals("AVAILABLE", groundService.getWire("JDX-1001").wire().status());
    }

    @Test
    void expiredTicketStillAllowsLawfulDetach() {
        long id = fullTicket("exp");
        confirm(id, 1, "exp-c1");
        borrow(id, "b-exp", 1L, "JDX-1001");
        groundService.attach(id, new AttachWireRequest(
                "a-exp", id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        clock.plus(Duration.ofHours(24));
        // 恰满24小时：步确认触发作废（独立事务提交），但实物不自动恢复，仍挂接
        ApiException expired = assertThrows(ApiException.class, () -> confirm(id, 3, "exp-c3"));
        assertEquals("TICKET_EXPIRED", expired.code());
        assertEquals("EXPIRED", orderService.get(id).ticket().status());
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());

        // 作废票上仍须合法拆除（引用 REMOVE_GROUND 步，双人确认）
        Instant now = Instant.now(clock);
        WireStepResult detached = groundService.detach(id, new DetachWireRequest(
                "d-exp", id, 3L, 3, "JDX-1001", "OP-01", "GD-01", now));
        assertEquals("REMOVED_PENDING_RETURN", detached.wireStatus());
        groundService.returnWires(id, new ReturnWiresRequest(
                "r-exp", id, 4L, List.of("JDX-1001"), "KEEPER-01", now));
        assertEquals("AVAILABLE", groundService.getWire("JDX-1001").wire().status());
    }

    // ---------- 并发：竞争借用不双占 ----------

    @Test
    void concurrentBorrowNeverDoubleOccupies() throws Exception {
        long ticketA = draftTicket("ca", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        long ticketB = draftTicket("cb", "INT-1102", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable borrowA = () -> {
            ready.countDown();
            await(start);
            try {
                borrow(ticketA, "cb-a", 1L, "JDX-1004");
                success.incrementAndGet();
            } catch (ApiException e) {
                if ("WIRE_NOT_AVAILABLE".equals(e.code())) {
                    rejected.incrementAndGet();
                }
            }
        };
        Runnable borrowB = () -> {
            ready.countDown();
            await(start);
            try {
                borrow(ticketB, "cb-b", 1L, "JDX-1004");
                success.incrementAndGet();
            } catch (ApiException e) {
                if ("WIRE_NOT_AVAILABLE".equals(e.code())) {
                    rejected.incrementAndGet();
                }
            }
        };
        Future<?> f1 = pool.submit(borrowA);
        Future<?> f2 = pool.submit(borrowB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, success.get());
        assertEquals(1, rejected.get());
        Integer loans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_loan WHERE wire_code = 'JDX-1004'", Integer.class);
        assertEquals(1, loans);
        assertNotNull(groundService.getWire("JDX-1004").wire().occupiedByTicket());
    }

    // ---------- 并发：挂接与送电不得既通过送电又留下挂接 ----------

    @Test
    void concurrentAttachAndEnergizeCannotBothSucceed() throws Exception {
        long id = fullTicket("race");
        confirm(id, 1, "race-c1");
        borrow(id, "b-race", 1L, "JDX-1001");
        // 票步推进到最后一步：挂线步、拆线步均已确认，当前应执行送电步，实物尚未挂接
        confirm(id, 2, "race-c2");
        confirm(id, 3, "race-c3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger attachOk = new AtomicInteger();
        AtomicInteger energizeOk = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable attach = () -> {
            ready.countDown();
            await(start);
            try {
                // 引用本票已执行的挂线步，现场补挂实物
                groundService.attach(id, new AttachWireRequest(
                        "a-race", id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
                attachOk.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Runnable energize = () -> {
            ready.countDown();
            await(start);
            try {
                confirm(id, 4, "race-c4");
                energizeOk.incrementAndGet();
            } catch (ApiException e) {
                rejected.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(attach);
        Future<?> f2 = pool.submit(energize);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, attachOk.get() + energizeOk.get(), "挂接与送电只能一方成功");
        boolean wireApplied = "APPLIED".equals(groundService.getWire("JDX-1001").wire().status());
        boolean energized = "CLOSED".equals(jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        assertFalse(wireApplied && energized, "不得既通过送电又留下挂接");
        if (energizeOk.get() == 1) {
            assertTrue(energized);
            assertFalse(wireApplied);
        } else {
            assertTrue(wireApplied);
            assertEquals("OPEN", jdbc.queryForObject(
                    "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        }
    }

    // ---------- 幂等：同键同参重放冻结结果，异参 409，失败不占键 ----------

    @Test
    void idempotentBorrowReplayFreezesResult() throws Exception {
        long ticket = draftTicket("idem", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "idem-borrow",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1001", "JDX-1002"),
                "occurredAt", T1.toString()));
        MvcResult r1 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        // 只借用一次：两条 loan，版本停在 2（重放不再次加1）
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_loan", Integer.class));
        assertEquals(2, groundService.getWire("JDX-1001").wire().version());
        assertEquals(2, groundService.getWire("JDX-1002").wire().version());
    }

    @Test
    void idempotentSameKeyDifferentParamsConflict() throws Exception {
        long ticket = draftTicket("idemx", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body1 = mapper.writeValueAsString(Map.of(
                "requestId", "idem-x",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1001"),
                "occurredAt", T1.toString()));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());
        // 同键改时刻（精确时刻属于指纹）
        String body2 = mapper.writeValueAsString(Map.of(
                "requestId", "idem-x",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1001"),
                "occurredAt", Instant.parse("2026-09-26T02:00:00Z").toString()));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
        // 集合内容不同也 409：新键先成功借用 JDX-1002（版本1），再以同键换编号
        String body3 = mapper.writeValueAsString(Map.of(
                "requestId", "idem-y",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1002"),
                "occurredAt", T1.toString()));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body3))
                .andExpect(status().isOk());
        String body3Changed = mapper.writeValueAsString(Map.of(
                "requestId", "idem-y",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1003"),
                "occurredAt", T1.toString()));
        MvcResult mismatch = mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body3Changed))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(mismatch.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH"));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        long ticket = draftTicket("idemf", "INT-1101", new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"));
        String badVersion = mapper.writeValueAsString(Map.of(
                "requestId", "idem-fail",
                "ticketId", ticket,
                "expectedVersion", 7,
                "wireCodes", List.of("JDX-1001"),
                "occurredAt", T1.toString()));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(badVersion))
                .andExpect(status().isUnprocessableEntity());
        // 同键改为合法参数后成功，证明失败未占键
        String good = mapper.writeValueAsString(Map.of(
                "requestId", "idem-fail",
                "ticketId", ticket,
                "expectedVersion", 1,
                "wireCodes", List.of("JDX-1001"),
                "occurredAt", T1.toString()));
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_loan", Integer.class));
    }

    @Test
    void idempotentAttachReplayDoesNotDuplicateEvidence() throws Exception {
        long id = fullTicket("idema");
        confirm(id, 1, "idema-c1");
        borrow(id, "b-idema", 1L, "JDX-1001");
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "idem-attach",
                "ticketId", id,
                "expectedVersion", 2,
                "stepSeq", 2,
                "wireCode", "JDX-1001",
                "operatorId", "OP-01",
                "guardianId", "GD-01",
                "occurredAt", T1.toString()));
        mockMvc.perform(post("/api/ground-wires/attach")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ground-wires/attach")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_evidence", Integer.class));
        assertEquals(3, groundService.getWire("JDX-1001").wire().version());
    }

    @Test
    void invalidJsonAndUnknownResourcesReturn400And404() throws Exception {
        mockMvc.perform(post("/api/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ground-wires/NO-SUCH-WIRE"))
                .andExpect(status().isNotFound());
    }
}
