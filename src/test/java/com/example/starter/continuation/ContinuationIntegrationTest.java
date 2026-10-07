package com.example.starter.continuation;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.continuation.ContinuationDtos.ApplyRequest;
import com.example.starter.continuation.ContinuationDtos.ConfirmRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.IssueRequest;
import com.example.starter.continuation.ContinuationDtos.RemainingStepSpec;
import com.example.starter.continuation.ContinuationDtos.WithdrawRequest;
import com.example.starter.error.ApiException;
import com.example.starter.ground.GroundWireDtos.AttachWireRequest;
import com.example.starter.ground.GroundWireDtos.BorrowWiresRequest;
import com.example.starter.ground.GroundWireDtos.DetachWireRequest;
import com.example.starter.ground.GroundWireDtos.ReturnWiresRequest;
import com.example.starter.ground.GroundWireService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.telemetry.TelemetryDtos.TelemetryRequest;
import com.example.starter.telemetry.TelemetryService;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;
import com.example.starter.ticket.TicketDtos.TicketDetailView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 余步续票集成测试：真实 H2（MODE=MySQL）验证余步冻结、双人确认、签发复核、
 * 双继承竞争、继承线拦送电、撤回、版本与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ContinuationIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private ContinuationService continuationService;
    @Autowired
    private SwitchingOrderService orderService;
    @Autowired
    private GroundWireService groundService;
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
        jdbc.update("DELETE FROM continuation_request");
        jdbc.update("DELETE FROM ground_wire_custody");
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

    private static StepSpec step(String op, String device, String expected) {
        return new StepSpec(op, device, expected);
    }

    private void confirm(long id, int seq, String reqId) {
        orderService.confirm(id, seq, new ConfirmStepRequest(reqId, "OP-01", "GD-01"));
    }

    /** 四步票：断开关→挂地线→拆地线→合开关送电。 */
    private long fullTicket(String reqPrefix) {
        long id = orderService.create(new CreateTicketRequest(
                "c-" + reqPrefix, "INT-1101", "续票测试原票", List.of(
                        step("OPEN_BREAKER", "BR-1101", "OPEN"),
                        step("APPLY_GROUND", "GW-1101", "APPLIED"),
                        step("REMOVE_GROUND", "GW-1101", "REMOVED"),
                        step("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-" + reqPrefix, "REV-01"));
        orderService.issue(id, new com.example.starter.ticket.TicketDtos.IssueRequest("iss-" + reqPrefix));
        return id;
    }

    /** 执行前两步并挂接实物后遥信不符转异常的原票；返回原票主键。 */
    private long exceptionTicketWithAppliedWire(String reqPrefix) {
        long id = fullTicket(reqPrefix);
        confirm(id, 1, reqPrefix + "-c1");
        groundService.borrow(id, new BorrowWiresRequest("b-" + reqPrefix, id, 1L, List.of("JDX-1001"), T1));
        groundService.attach(id, new AttachWireRequest(
                "a-" + reqPrefix, id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        confirm(id, 2, reqPrefix + "-c2");
        // 遥信回报与预期不符（预期已挂接，回报已拆除）→ 中止转异常，释放间隔锁，实物仍挂接
        var resp = telemetryService.record(new TelemetryRequest(
                "INT-1101", "GW-1101", T1, "REMOVED"));
        assertEquals("ABORTED", resp.result());
        assertEquals("EXCEPTION", orderService.get(id).ticket().status());
        return id;
    }

    private long sourceVersion(long ticketId) {
        return jdbc.queryForObject("SELECT version FROM switching_ticket WHERE id = ?", Long.class, ticketId);
    }

    private ContinuationView applyOk(String reqId, long sourceId) {
        return continuationService.apply(new ApplyRequest(
                reqId, sourceId, sourceVersion(sourceId), "INT-1101", "余步续票", null));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---------- 主流程：余步冻结 → 双人确认 → 签发交接 → 继承线拦送电 → 合法拆还 → 双票归档 ----------

    @Test
    void fullContinuationFlowFreezesStepsAndInheritsWireResponsibility() {
        long source = exceptionTicketWithAppliedWire("main");
        long sourceVer = sourceVersion(source);

        // 申请：冻结原步序、间隔与剩余步骤（seq 3、4 保持原步序）
        ContinuationView applied = applyOk("cont-main", source);
        assertEquals("PENDING", applied.status());
        assertEquals(1, applied.version());
        assertEquals(sourceVer, applied.sourceVersion());
        TicketDetailView newTicket = orderService.get(applied.newTicketId());
        assertEquals("DRAFT", newTicket.ticket().status());
        assertEquals(source, newTicket.ticket().continuationOf());
        assertEquals("INT-1101", newTicket.ticket().intervalId());
        assertEquals(List.of(3, 4), newTicket.steps().stream().map(s -> s.seq()).toList());
        assertEquals("REMOVE_GROUND", newTicket.steps().get(0).operationType());
        assertEquals("CLOSE_BREAKER", newTicket.steps().get(1).operationType());

        // 续票不可走普通审核/预发
        ApiException normalReview = assertThrows(ApiException.class, () ->
                orderService.review(applied.newTicketId(), new ReviewRequest("rv-bypass", "REV-X")));
        assertEquals("CONTINUATION_ONLY", normalReview.code());

        // 双人确认：两名不同审核人
        ContinuationView confirmed = continuationService.confirm(applied.continuationId(),
                new ConfirmRequest("cont-main-cf", 1L, "REV-A", "REV-B"));
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(2, confirmed.version());
        assertEquals("REVIEWED", orderService.get(applied.newTicketId()).ticket().status());

        // 签发生效：原子交接间隔执行权与实物责任
        ContinuationView effective = continuationService.issue(applied.continuationId(),
                new IssueRequest("cont-main-is", 2L));
        assertEquals("EFFECTIVE", effective.status());
        assertEquals(3, effective.version());
        TicketDetailView issued = orderService.get(applied.newTicketId());
        assertEquals("ISSUED", issued.ticket().status());
        assertEquals(T0, issued.ticket().issuedAt());
        assertEquals(T0.plus(SwitchingOrderService.VALIDITY), issued.ticket().expiresAt());
        assertEquals(applied.newTicketId(), orderService.activeTicketForInterval("INT-1101"));
        // 实物占用权转移给续票，原借用编号与历史证据不改
        var wire = groundService.getWire("JDX-1001");
        assertEquals(applied.newTicketId(), wire.wire().occupiedByTicket());
        assertEquals("APPLIED", wire.wire().status());
        assertEquals(4, wire.wire().version());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_custody WHERE successor_ticket_id = ? AND wire_code = 'JDX-1001'",
                Integer.class, applied.newTicketId()));
        assertEquals(source, wire.loans().get(0).ticketId());
        assertEquals(List.of("APPLIED"), wire.evidences().stream().map(e -> e.action()).toList());

        // 原票保持中止：不可重开或推进已做步骤
        assertEquals("EXCEPTION", orderService.get(source).ticket().status());
        assertEquals("DONE", orderService.get(source).steps().get(0).status());
        ApiException sourceConfirm = assertThrows(ApiException.class, () ->
                confirm(source, 3, "src-reopen"));
        assertEquals("TICKET_STATE", sourceConfirm.code());

        // 继承线拦送电：续票确认拆线步后实物仍挂接，送电步被实物联锁拦下（查整个间隔全部已挂接线）
        long cont = applied.newTicketId();
        confirm(cont, 3, "cont-c3");
        ApiException energize = assertThrows(ApiException.class, () -> confirm(cont, 4, "cont-c4"));
        assertEquals("GROUND_WIRE_STILL_APPLIED", energize.code());
        assertEquals("PENDING", orderService.get(cont).steps().get(1).status());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));

        // 续票合法拆除本继承链实物（引用本票 REMOVE_GROUND 步，双人确认）并归还
        groundService.detach(cont, new DetachWireRequest(
                "d-cont-main", cont, 4L, 3, "JDX-1001", "OP-01", "GD-01", T1));
        assertEquals("REMOVED_PENDING_RETURN", groundService.getWire("JDX-1001").wire().status());
        confirm(cont, 4, "cont-c4b");
        assertEquals("DONE", orderService.get(cont).steps().get(1).status());
        groundService.returnWires(cont, new ReturnWiresRequest(
                "r-cont-main", cont, 5L, List.of("JDX-1001"), "KEEPER-01", T1));
        assertEquals("AVAILABLE", groundService.getWire("JDX-1001").wire().status());
        // 归还核销登记在原借用记录上（历史不改，责任链一致）
        assertEquals("RETURNED", groundService.getWire("JDX-1001").loans().get(0).status());
        assertEquals("KEEPER-01", groundService.getWire("JDX-1001").loans().get(0).keeperId());

        // 续票归档：继承实物已核销；原票归档：借用已归还
        orderService.archive(cont, new ArchiveRequest("arc-cont"));
        assertEquals("ARCHIVED", orderService.get(cont).ticket().status());
        orderService.archive(source, new ArchiveRequest("arc-src"));
        assertEquals("ARCHIVED", orderService.get(source).ticket().status());
        // 已执行证据与历史轨迹保留可查：原票挂接证据、续票拆除证据各自独立
        assertEquals("DONE", orderService.get(source).steps().get(1).status());
        assertEquals(List.of("APPLIED"),
                groundService.ticketGround(source).evidences().stream().map(e -> e.action()).toList());
        assertEquals(List.of("REMOVED"),
                groundService.ticketGround(cont).evidences().stream().map(e -> e.action()).toList());
    }

    // ---------- 申请校验：原票状态、跨间隔、删改剩余步骤、无剩余、版本 ----------

    @Test
    void applyRejectsInvalidSourceStateCrossIntervalModifiedStepsAndVersion() {
        // 执行中票不可申请（独立间隔，避免占用 INT-1101 执行锁）
        long executing = orderService.create(new CreateTicketRequest(
                "c-exec", "INT-1102", "执行中票", List.of(step("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        orderService.review(executing, new ReviewRequest("rv-exec", "REV-01"));
        orderService.issue(executing, new com.example.starter.ticket.TicketDtos.IssueRequest("iss-exec"));
        ApiException state = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-s1", executing, sourceVersion(executing), "INT-1102", "续票", null)));
        assertEquals("SOURCE_STATE", state.code());
        assertEquals(409, state.status().value());

        long source = exceptionTicketWithAppliedWire("chk");
        // 跨间隔拒绝
        ApiException cross = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-s2", source, sourceVersion(source), "INT-9999", "续票", null)));
        assertEquals("CROSS_INTERVAL", cross.code());
        // 删改剩余步骤拒绝
        ApiException modified = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-s3", source, sourceVersion(source), "INT-1101", "续票",
                        List.of(new RemainingStepSpec(3, "REMOVE_GROUND", "GW-1101", "REMOVED"),
                                new RemainingStepSpec(4, "CLOSE_BREAKER", "BR-1101", "OPEN")))));
        assertEquals("STEPS_MODIFIED", modified.code());
        // 声明与原票剩余步骤完全一致则放行
        ContinuationView ok = continuationService.apply(new ApplyRequest(
                "cont-s4", source, sourceVersion(source), "INT-1101", "续票",
                List.of(new RemainingStepSpec(3, "REMOVE_GROUND", "GW-1101", "REMOVED"),
                        new RemainingStepSpec(4, "CLOSE_BREAKER", "BR-1101", "CLOSED"))));
        assertEquals("PENDING", ok.status());
        // 原票版本快照不符拒绝
        ApiException version = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-s5", source, 99L, "INT-1101", "续票", null)));
        assertEquals("VERSION_MISMATCH", version.code());
        assertEquals(409, version.status().value());
    }

    @Test
    void applyRejectsArchivedSourceAndNoRemainingSteps() {
        // 无剩余步骤：一步票执行完后转异常
        long done = orderService.create(new CreateTicketRequest(
                "c-done", "INT-1101", "一步票", List.of(step("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        orderService.review(done, new ReviewRequest("rv-done", "REV-01"));
        orderService.issue(done, new com.example.starter.ticket.TicketDtos.IssueRequest("iss-done"));
        confirm(done, 1, "done-c1");
        telemetryService.record(new TelemetryRequest("INT-1101", "BR-1101", T1, "CLOSED"));
        assertEquals("EXCEPTION", orderService.get(done).ticket().status());
        ApiException noRemaining = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-nr", done, sourceVersion(done), "INT-1101", "续票", null)));
        assertEquals("NO_REMAINING_STEPS", noRemaining.code());
        assertEquals(422, noRemaining.status().value());

        // 已归档原票不可申请
        orderService.archive(done, new ArchiveRequest("arc-done"));
        ApiException archived = assertThrows(ApiException.class, () ->
                continuationService.apply(new ApplyRequest(
                        "cont-arc", done, sourceVersion(done), "INT-1101", "续票", null)));
        assertEquals("SOURCE_STATE", archived.code());
    }

    // ---------- 双人确认与版本 ----------

    @Test
    void confirmRequiresTwoDifferentReviewersAndVersionChecked() {
        long source = exceptionTicketWithAppliedWire("dc");
        ContinuationView applied = applyOk("cont-dc", source);
        ApiException same = assertThrows(ApiException.class, () ->
                continuationService.confirm(applied.continuationId(),
                        new ConfirmRequest("dc-cf1", 1L, "REV-A", "REV-A")));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", same.code());
        assertEquals(400, same.status().value());
        ApiException stale = assertThrows(ApiException.class, () ->
                continuationService.confirm(applied.continuationId(),
                        new ConfirmRequest("dc-cf2", 9L, "REV-A", "REV-B")));
        assertEquals("VERSION_MISMATCH", stale.code());
        assertEquals(409, stale.status().value());
        ContinuationView confirmed = continuationService.confirm(applied.continuationId(),
                new ConfirmRequest("dc-cf3", 1L, "REV-A", "REV-B"));
        assertEquals(2, confirmed.version());
        // 旧版签发 409
        ApiException staleIssue = assertThrows(ApiException.class, () ->
                continuationService.issue(applied.continuationId(), new IssueRequest("dc-is1", 1L)));
        assertEquals("VERSION_MISMATCH", staleIssue.code());
    }

    // ---------- 签发复核：原票版本与当前设备状态，不以旧拟票快照跳过五防 ----------

    @Test
    void issueRechecksSourceVersionAndCurrentDeviceState() {
        // 原票版本在申请后被改动 → 拒绝生效
        long source = exceptionTicketWithAppliedWire("ver");
        ContinuationView applied = applyOk("cont-ver", source);
        continuationService.confirm(applied.continuationId(), new ConfirmRequest("ver-cf", 1L, "REV-A", "REV-B"));
        jdbc.update("UPDATE switching_ticket SET version = version + 1 WHERE id = ?", source);
        ApiException changed = assertThrows(ApiException.class, () ->
                continuationService.issue(applied.continuationId(), new IssueRequest("ver-is", 2L)));
        assertEquals("SOURCE_CHANGED", changed.code());
        assertEquals(409, changed.status().value());
        assertEquals("CONFIRMED", continuationService.get(applied.continuationId()).status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));

        // 当前设备状态被五防闭锁 → 拒绝生效，不以申请时快照放行
        long twoStep = orderService.create(new CreateTicketRequest(
                "c-dev", "INT-1101", "两步票", List.of(
                        step("OPEN_BREAKER", "BR-1101", "OPEN"),
                        step("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(twoStep, new ReviewRequest("rv-dev", "REV-01"));
        orderService.issue(twoStep, new com.example.starter.ticket.TicketDtos.IssueRequest("iss-dev"));
        confirm(twoStep, 1, "dev-c1");
        telemetryService.record(new TelemetryRequest(
                "INT-1101", "BR-1101", Instant.parse("2026-09-26T02:00:00Z"), "CLOSED"));
        assertEquals("EXCEPTION", orderService.get(twoStep).ticket().status());
        ContinuationView applied2 = applyOk("cont-dev", twoStep);
        continuationService.confirm(applied2.continuationId(), new ConfirmRequest("dev-cf", 1L, "REV-A", "REV-B"));
        // 申请后现场实际挂线（设备实时状态变化），签发复核必须拦下送电步
        jdbc.update("UPDATE device_state SET state = 'APPLIED' WHERE device_id = 'GW-1101'");
        ApiException blocked = assertThrows(ApiException.class, () ->
                continuationService.issue(applied2.continuationId(), new IssueRequest("dev-is", 2L)));
        assertEquals("INTERLOCK_BLOCKED", blocked.code());
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ?", String.class, applied2.newTicketId()));
        assertEquals("CONFIRMED", continuationService.get(applied2.continuationId()).status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
    }

    // ---------- 双继承竞争：两份续票同时批准只一个后继 ----------

    @Test
    void concurrentIssueOnlyOneSuccessor() throws Exception {
        long source = exceptionTicketWithAppliedWire("race");
        ContinuationView a = applyOk("cont-race-a", source);
        ContinuationView b = applyOk("cont-race-b", source);
        continuationService.confirm(a.continuationId(), new ConfirmRequest("race-cfa", 1L, "REV-A", "REV-B"));
        continuationService.confirm(b.continuationId(), new ConfirmRequest("race-cfb", 1L, "REV-C", "REV-D"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable issueA = () -> {
            ready.countDown();
            await(start);
            try {
                continuationService.issue(a.continuationId(), new IssueRequest("race-isa", 2L));
                success.incrementAndGet();
            } catch (ApiException e) {
                conflict.incrementAndGet();
            }
        };
        Runnable issueB = () -> {
            ready.countDown();
            await(start);
            try {
                continuationService.issue(b.continuationId(), new IssueRequest("race-isb", 2L));
                success.incrementAndGet();
            } catch (ApiException e) {
                conflict.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(issueA);
        Future<?> f2 = pool.submit(issueB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, success.get(), "双继承同时批准只能一个生效");
        assertEquals(1, conflict.get(), "失败方必须收到冲突");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM continuation_request WHERE source_ticket_id = ? AND status = 'EFFECTIVE'",
                Integer.class, source));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM interval_execution_lock WHERE interval_id = 'INT-1101'", Integer.class));
        // 实物责任只接续给生效续票，不双重转移
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_custody WHERE source_ticket_id = ?", Integer.class, source));
        Long holder = jdbc.queryForObject(
                "SELECT occupied_by_ticket FROM ground_wire WHERE code = 'JDX-1001'", Long.class);
        Long effectiveTicket = jdbc.queryForObject(
                "SELECT new_ticket_id FROM continuation_request WHERE source_ticket_id = ? AND status = 'EFFECTIVE'",
                Long.class, source);
        assertEquals(effectiveTicket, holder);
    }

    // ---------- 撤回：未生效可撤回，不释放原间隔或实物；已生效不能撤回 ----------

    @Test
    void withdrawOnlyBeforeEffectiveAndKeepsIntervalAndWires() {
        long source = exceptionTicketWithAppliedWire("wd");
        ContinuationView applied = applyOk("cont-wd", source);
        ContinuationView withdrawn = continuationService.withdraw(applied.continuationId(),
                new WithdrawRequest("wd-w1", 1L));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(2, withdrawn.version());
        // 不释放原间隔或实物：实物占用仍在原票名下，间隔锁未交给续票
        assertEquals(source, groundService.getWire("JDX-1001").wire().occupiedByTicket());
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_custody", Integer.class));
        // 已撤回不可再撤回
        assertThrows(ApiException.class, () -> continuationService.withdraw(applied.continuationId(),
                new WithdrawRequest("wd-w2", 2L)));
        // 撤回后同原票可重新申请
        ContinuationView reapplied = applyOk("cont-wd2", source);
        assertEquals("PENDING", reapplied.status());

        // 已生效不能撤回
        continuationService.confirm(reapplied.continuationId(), new ConfirmRequest("wd-cf", 1L, "REV-A", "REV-B"));
        continuationService.issue(reapplied.continuationId(), new IssueRequest("wd-is", 2L));
        ApiException effective = assertThrows(ApiException.class, () ->
                continuationService.withdraw(reapplied.continuationId(), new WithdrawRequest("wd-w3", 3L)));
        assertEquals("CONTINUATION_STATE", effective.code());
        assertEquals(409, effective.status().value());
        assertEquals("EFFECTIVE", continuationService.get(reapplied.continuationId()).status());
    }

    // ---------- 不得代管其他票的线 ----------

    @Test
    void continuationCannotManageOtherTicketsWires() {
        long source = exceptionTicketWithAppliedWire("oth");
        ContinuationView applied = applyOk("cont-oth", source);
        continuationService.confirm(applied.continuationId(), new ConfirmRequest("oth-cf", 1L, "REV-A", "REV-B"));
        continuationService.issue(applied.continuationId(), new IssueRequest("oth-is", 2L));
        long cont = applied.newTicketId();

        // 另一张票借用 JDX-1002
        long other = orderService.create(new CreateTicketRequest(
                "c-oth2", "INT-1102", "其他票", List.of(step("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        groundService.borrow(other, new BorrowWiresRequest("b-oth2", other, 1L, List.of("JDX-1002"), T1));

        // 续票归还其他票的线 → 拒绝
        ApiException ret = assertThrows(ApiException.class, () -> groundService.returnWires(cont,
                new ReturnWiresRequest("r-oth", cont, 2L, List.of("JDX-1002"), "KEEPER-01", T1)));
        assertEquals("WIRE_NOT_BORROWED_BY_TICKET", ret.code());
        // 续票拆除其他票的线 → 拒绝
        ApiException det = assertThrows(ApiException.class, () -> groundService.detach(cont,
                new DetachWireRequest("d-oth", cont, 2L, 3, "JDX-1002", "OP-01", "GD-01", T1)));
        assertEquals("WIRE_NOT_APPLIED", det.code());
        // 其他票的线不受影响
        assertEquals(other, groundService.getWire("JDX-1002").wire().occupiedByTicket());
        assertEquals("BORROWED", groundService.getWire("JDX-1002").wire().status());
    }

    // ---------- 原票因继承得以归档；续票未核销继承实物不得归档 ----------

    @Test
    void sourceArchivesViaInheritanceButContinuationMustSettleInheritedWires() {
        long source = exceptionTicketWithAppliedWire("inh");
        ContinuationView applied = applyOk("cont-inh", source);
        continuationService.confirm(applied.continuationId(), new ConfirmRequest("inh-cf", 1L, "REV-A", "REV-B"));
        continuationService.issue(applied.continuationId(), new IssueRequest("inh-is", 2L));
        long cont = applied.newTicketId();

        // 续票未归还继承实物前不得归档
        ApiException contArchive = assertThrows(ApiException.class, () ->
                orderService.archive(cont, new ArchiveRequest("arc-inh-c1")));
        assertEquals("INHERITED_WIRES_NOT_RETURNED", contArchive.code());

        // 原票因继承得以登记中止归档（实物责任已接续，历史可查）
        orderService.archive(source, new ArchiveRequest("arc-inh-s"));
        assertEquals("ARCHIVED", orderService.get(source).ticket().status());
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());
        assertEquals(cont, groundService.getWire("JDX-1001").wire().occupiedByTicket());

        // 原票归档后实物历史继续可查
        assertEquals(1, groundService.getWire("JDX-1001").loans().size());
        assertEquals(1, groundService.getWire("JDX-1001").evidences().size());
    }

    // ---------- 幂等：同键同参重放，异参 409，失败不占键 ----------

    @Test
    void idempotentApplyReplayMismatchAndFailureNotOccupyingKey() throws Exception {
        long source = exceptionTicketWithAppliedWire("idem");
        long ver = sourceVersion(source);
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "idem-cont",
                "sourceTicketId", source,
                "expectedSourceVersion", ver,
                "intervalId", "INT-1101",
                "title", "幂等续票"));
        MvcResult r1 = mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode n1 = mapper.readTree(r1.getResponse().getContentAsString());
        JsonNode n2 = mapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(n1.get("continuationId").asLong(), n2.get("continuationId").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_request", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM switching_ticket WHERE continuation_of = ?", Integer.class, source));

        // 同键异参 409
        String changed = mapper.writeValueAsString(Map.of(
                "requestId", "idem-cont",
                "sourceTicketId", source,
                "expectedSourceVersion", ver,
                "intervalId", "INT-1101",
                "title", "被篡改的续票"));
        mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(result -> assertTrue(
                        result.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_request", Integer.class));

        // 失败不占键：无剩余步骤申请 422 后，同键改合法参数成功
        long done = orderService.create(new CreateTicketRequest(
                "c-idf", "INT-1102", "一步票", List.of(step("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        orderService.review(done, new ReviewRequest("rv-idf", "REV-01"));
        orderService.issue(done, new com.example.starter.ticket.TicketDtos.IssueRequest("iss-idf"));
        confirm(done, 1, "idf-c1");
        jdbc.update("UPDATE switching_ticket SET status = 'EXCEPTION', version = version + 1 WHERE id = ?", done);
        jdbc.update("DELETE FROM interval_execution_lock");
        String bad = mapper.writeValueAsString(Map.of(
                "requestId", "idem-cont-f",
                "sourceTicketId", done,
                "expectedSourceVersion", sourceVersion(done),
                "intervalId", "INT-1102",
                "title", "无剩余续票"));
        mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity());
        String good = mapper.writeValueAsString(Map.of(
                "requestId", "idem-cont-f",
                "sourceTicketId", source,
                "expectedSourceVersion", ver,
                "intervalId", "INT-1101",
                "title", "合法续票"));
        mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_request", Integer.class));
    }
}
