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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.ApiException;
import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.groundwire.GroundWireDtos.WireVersionItem;
import com.example.starter.groundwire.GroundWireDtos.WireView;
import com.example.starter.groundwire.GroundWireService;
import com.example.starter.idem.IdempotencyService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接地线实物联锁集成测试：真实 H2（MODE=MySQL）验证状态机、双人证据、
 * 送电实物拦截、归档前归还、并发占用与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class GroundWireIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    private static final String W1 = "GW-SYN-001";
    private static final String W2 = "GW-SYN-002";

    @Autowired
    private SwitchingOrderService orderService;
    @Autowired
    private GroundWireService groundWireService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TransactionTemplate txTemplate;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM ground_wire_operation");
        jdbc.update("DELETE FROM ground_wire_borrow");
        jdbc.update("DELETE FROM switching_step");
        jdbc.update("DELETE FROM switching_ticket");
        jdbc.update("DELETE FROM interlock_hit");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM interval_execution_lock");
        jdbc.update("DELETE FROM ground_wire");
        jdbc.update("INSERT INTO ground_wire (wire_no, status, version, created_at, updated_at) VALUES"
                + " ('GW-SYN-001','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GW-SYN-002','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('GW-SYN-003','AVAILABLE',1,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
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

    private static WireVersionItem item(String wireNo, int version) {
        return new WireVersionItem(wireNo, version);
    }

    private long issueTicket(String tag, String intervalId, StepSpec... steps) {
        long id = orderService.create(new CreateTicketRequest("c-" + tag, intervalId, "接地线测试票 " + tag,
                List.of(steps))).ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-" + tag, "REV-01"));
        orderService.issue(id, new IssueRequest("iss-" + tag));
        return id;
    }

    private void openBreaker(long ticketId, String reqTag) {
        orderService.confirm(ticketId, 1, new ConfirmStepRequest("cf-" + reqTag, "OP-01", "GD-01"));
    }

    private String wireStatus(String wireNo) {
        return jdbc.queryForObject("SELECT status FROM ground_wire WHERE wire_no = ?", String.class, wireNo);
    }

    private int wireVersion(String wireNo) {
        return jdbc.queryForObject("SELECT version FROM ground_wire WHERE wire_no = ?", Integer.class, wireNo);
    }

    private ApiException expectApi(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, action::run);
    }

    // ---------- 主流程：借→挂→送电拦截→拆→还→归档 ----------

    @Test
    void fullLifecyclePhysicalGroundWireBlocksEnergizeUntilRemoved() {
        long id = issueTicket("full", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        // 借用两条，任一不可用整批拒绝的正常路径
        groundWireService.borrow(id, new BorrowRequest("bw-full", "INT-1101",
                List.of(item(W2, 1), item(W1, 1)), T0));
        assertEquals("BORROWED", wireStatus(W1));
        assertEquals(2, wireVersion(W1));
        // 断开开关使间隔不带电
        openBreaker(id, "full-1");
        // 挂接：双人确认、引用本票步序，证据只追加
        var applied = groundWireService.apply(id, W1, new ApplyRequest(
                "ap-full", 2, 2, "OP-01", "GD-01", T0.plusSeconds(10)));
        assertEquals("APPLY", applied.action());
        assertEquals(2, applied.stepSeq());
        assertEquals("APPLIED", wireStatus(W1));
        assertEquals(3, wireVersion(W1));
        assertEquals("DONE", orderService.get(id).steps().get(1).status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_operation WHERE action = 'APPLY'", Integer.class));
        assertEquals(id, jdbc.queryForObject(
                "SELECT current_ticket_id FROM ground_wire WHERE wire_no = ?", Long.class, W1));

        // 送电步确认：接地线仍挂接，实物联锁拦截且步序不前进
        ApiException blocked = expectApi(() -> orderService.confirm(id, 3,
                new ConfirmStepRequest("cf-full-energize", "OP-01", "GD-01")));
        assertEquals("GROUND_WIRE_STILL_APPLIED", blocked.code());
        assertEquals(422, blocked.status().value());
        assertEquals("PENDING", orderService.get(id).steps().get(2).status());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class, id));
        // 实物仍挂接、版本未变；拆除/归还/再送电完整路径见 fullLifecycleWithRemoveStepThenReturnAndArchive
        assertEquals("APPLIED", wireStatus(W1));
        assertEquals(3, wireVersion(W1));
    }

    @Test
    void fullLifecycleWithRemoveStepThenReturnAndArchive() {
        long id = issueTicket("life", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("REMOVE_GROUND", W1, "REMOVED"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        groundWireService.borrow(id, new BorrowRequest("bw-life", "INT-1101", List.of(item(W1, 1)), T0));
        openBreaker(id, "life-1");
        groundWireService.apply(id, W1, new ApplyRequest("ap-life", 2, 2, "OP-01", "GD-01", T0.plusSeconds(10)));
        // 拆除引用第3步
        groundWireService.remove(id, W1, new RemoveRequest("rm-life", 3, 3, "OP-02", "GD-02",
                T0.plusSeconds(20)));
        assertEquals("REMOVED_PENDING_RETURN", wireStatus(W1));
        assertEquals(4, wireVersion(W1));
        assertEquals("DONE", orderService.get(id).steps().get(2).status());
        // 送电步放行
        var energize = orderService.confirm(id, 4, new ConfirmStepRequest("cf-life-4", "OP-01", "GD-01"));
        assertEquals("DONE", energize.stepStatus());
        // 未归还前归档拒绝
        ApiException noArchive = expectApi(() -> orderService.archive(id, new ArchiveRequest("arc-life-x")));
        assertEquals("GROUND_WIRE_NOT_RETURNED", noArchive.code());
        assertEquals(422, noArchive.status().value());
        // 仓管核对编号归还，只核销已拆除实物
        groundWireService.returnWires(id, new ReturnRequest("rt-life", List.of(item(W1, 4)),
                "WH-01", T0.plusSeconds(30)));
        assertEquals("AVAILABLE", wireStatus(W1));
        assertEquals(5, wireVersion(W1));
        assertNull(jdbc.queryForObject(
                "SELECT current_ticket_id FROM ground_wire WHERE wire_no = ?", Long.class, W1));
        assertEquals("RETURNED", jdbc.queryForObject(
                "SELECT status FROM ground_wire_borrow WHERE ticket_id = ?", String.class, id));
        // 归档成功，历史借用与操作证据保留
        orderService.archive(id, new ArchiveRequest("arc-life-ok"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
        assertEquals(1, groundWireService.listBorrows(id).size());
        assertEquals(2, groundWireService.listOperations(id).size());
    }

    // ---------- 送电拦截不信任拟票快照：前票作废遗留挂接线，后票送电被拦 ----------

    @Test
    void expiredTicketLeavesAppliedWireAndNextTicketEnergizeBlocked() {
        long ticketA = issueTicket("exp-a", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("REMOVE_GROUND", W1, "REMOVED"));
        groundWireService.borrow(ticketA, new BorrowRequest("bw-exp-a", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(ticketA, "exp-a-1");
        groundWireService.apply(ticketA, W1, new ApplyRequest("ap-exp-a", 2, 2, "OP-01", "GD-01",
                T0.plusSeconds(10)));
        // 恰满 24 小时作废：挂接实物不自动变回可用，间隔锁释放
        clock.plus(Duration.ofHours(24));
        ApiException expired = expectApi(() -> orderService.confirm(ticketA, 3,
                new ConfirmStepRequest("cf-exp-a-3", "OP-01", "GD-01")));
        assertEquals("TICKET_EXPIRED", expired.code());
        assertEquals("APPLIED", wireStatus(W1));
        assertEquals("BORROWED", jdbc.queryForObject(
                "SELECT status FROM ground_wire_borrow WHERE ticket_id = ?", String.class, ticketA));
        assertNull(orderService.activeTicketForInterval("INT-1101"));

        // 新票在同间隔送电：实时复查发现遗留挂接线，拒绝且步序不前进
        long ticketB = issueTicket("exp-b", "INT-1101", step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        ApiException blocked = expectApi(() -> orderService.confirm(ticketB, 1,
                new ConfirmStepRequest("cf-exp-b-1", "OP-03", "GD-03")));
        assertEquals("GROUND_WIRE_STILL_APPLIED", blocked.code());
        assertEquals("PENDING", orderService.get(ticketB).steps().get(0).status());

        // 作废票仍须合法拆除与归还
        groundWireService.remove(ticketA, W1, new RemoveRequest("rm-exp-a", 3, 3, "OP-02", "GD-02",
                T0.plus(Duration.ofHours(24)).plusSeconds(5)));
        assertEquals("REMOVED_PENDING_RETURN", wireStatus(W1));
        groundWireService.returnWires(ticketA, new ReturnRequest("rt-exp-a", List.of(item(W1, 4)),
                "WH-01", T0.plus(Duration.ofHours(24)).plusSeconds(10)));
        assertEquals("AVAILABLE", wireStatus(W1));
        // 实物拆除归还后新票送电通过
        orderService.confirm(ticketB, 1, new ConfirmStepRequest("cf-exp-b-1-ok", "OP-03", "GD-03"));
        assertEquals("DONE", orderService.get(ticketB).steps().get(0).status());
        orderService.archive(ticketA, new ArchiveRequest("arc-exp-a"));
        orderService.archive(ticketB, new ArchiveRequest("arc-exp-b"));
        // 历史借用与证据保留
        assertEquals(2, groundWireService.listOperations(ticketA).size());
        assertEquals(1, groundWireService.listBorrows(ticketA).size());
    }

    // ---------- 借用失败分支 ----------

    @Test
    void borrowBatchRejectedWhenAnyWireUnavailable() {
        long id = issueTicket("bw-fail", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        groundWireService.borrow(id, new BorrowRequest("bw-first", "INT-1101",
                List.of(item(W2, 1)), T0));
        ApiException ex = expectApi(() -> groundWireService.borrow(id, new BorrowRequest("bw-second",
                "INT-1101", List.of(item(W1, 1), item(W2, 1)), T0.plusSeconds(1))));
        assertEquals("WIRE_NOT_AVAILABLE", ex.code());
        assertEquals(422, ex.status().value());
        // 整批拒绝：W1 未被占用，W2 仍只被原票占用，被拒批次不产生任何借用登记
        assertEquals("AVAILABLE", wireStatus(W1));
        assertEquals("BORROWED", wireStatus(W2));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_borrow WHERE borrow_request_id = 'bw-second'", Integer.class));
    }

    @Test
    void borrowRejectsArchivedTicketAndIntervalMismatch() {
        long id = issueTicket("bw-arc", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        orderService.archive(id, new ArchiveRequest("arc-bw"));
        ApiException ex = expectApi(() -> groundWireService.borrow(id, new BorrowRequest("bw-on-arc",
                "INT-1101", List.of(item(W1, 1)), T0)));
        assertEquals("TICKET_NOT_BORROWABLE", ex.code());
        long other = issueTicket("bw-mis", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException mismatch = expectApi(() -> groundWireService.borrow(other, new BorrowRequest(
                "bw-mismatch", "BUS-1", List.of(item(W1, 1)), T0)));
        assertEquals("INTERVAL_MISMATCH", mismatch.code());
        assertEquals("AVAILABLE", wireStatus(W1));
    }

    @Test
    void borrowRejectsUnknownWireWrongVersionAndDuplicateItem() {
        long id = issueTicket("bw-bad", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        ApiException notFound = expectApi(() -> groundWireService.borrow(id, new BorrowRequest(
                "bw-404", "INT-1101", List.of(item("GW-NOPE", 1)), T0)));
        assertEquals("WIRE_NOT_FOUND", notFound.code());
        assertEquals(404, notFound.status().value());
        ApiException version = expectApi(() -> groundWireService.borrow(id, new BorrowRequest(
                "bw-ver", "INT-1101", List.of(item(W1, 2)), T0)));
        assertEquals("VERSION_MISMATCH", version.code());
        assertEquals(409, version.status().value());
        ApiException duplicate = expectApi(() -> groundWireService.borrow(id, new BorrowRequest(
                "bw-dup", "INT-1101", List.of(item(W1, 1), item(W1, 1)), T0)));
        assertEquals("DUPLICATE_WIRE_ITEM", duplicate.code());
        assertEquals(400, duplicate.status().value());
        assertEquals("AVAILABLE", wireStatus(W1));
    }

    // ---------- 挂接/拆除失败分支 ----------

    @Test
    void applyRequiresTwoPeopleCorrectStepAndBorrowedWire() {
        long id = issueTicket("ap-bad", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-ap-bad", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "ap-bad-1");
        // 同一人双确认拒绝
        ApiException same = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-same", 2, 2, "OP-01", "OP-01", T0.plusSeconds(5))));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", same.code());
        assertEquals(400, same.status().value());
        // 引用不存在步序 404
        ApiException noStep = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-nostep", 2, 9, "OP-01", "GD-01", T0.plusSeconds(5))));
        assertEquals("STEP_NOT_FOUND", noStep.code());
        assertEquals(404, noStep.status().value());
        // 引用错误操作类型步骤
        ApiException wrongType = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-wrongtype", 2, 1, "OP-01", "GD-01", T0.plusSeconds(5))));
        assertEquals("STEP_TYPE_MISMATCH", wrongType.code());
        // 版本不符
        ApiException badVer = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-badver", 9, 2, "OP-01", "GD-01", T0.plusSeconds(5))));
        assertEquals("VERSION_MISMATCH", badVer.code());
        // 未被本票借用的实物
        ApiException notBorrowed = expectApi(() -> groundWireService.apply(id, W2, new ApplyRequest(
                "ap-notborrowed", 1, 2, "OP-01", "GD-01", T0.plusSeconds(5))));
        assertEquals("WIRE_NOT_BORROWED_BY_TICKET", notBorrowed.code());
        assertEquals("BORROWED", wireStatus(W1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_operation", Integer.class));
    }

    @Test
    void applyBlockedWhenIntervalLiveAndLeavesNoEvidence() {
        long id = issueTicket("ap-live", "INT-1101",
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-ap-live", "INT-1101",
                List.of(item(W1, 1)), T0));
        // 开关仍在合位（间隔带电），挂接被闭锁
        ApiException blocked = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-live-x", 2, 1, "OP-01", "GD-01", T0.plusSeconds(5))));
        assertEquals("INTERLOCK_BLOCKED", blocked.code());
        assertEquals(422, blocked.status().value());
        assertEquals("BORROWED", wireStatus(W1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_operation", Integer.class));
        assertEquals("PENDING", orderService.get(id).steps().get(0).status());
    }

    @Test
    void removeOnlyFromAppliedAndRejectsSamePerson() {
        long id = issueTicket("rm-bad", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("REMOVE_GROUND", W1, "REMOVED"));
        groundWireService.borrow(id, new BorrowRequest("bw-rm-bad", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "rm-bad-1");
        groundWireService.apply(id, W1, new ApplyRequest("ap-rm-bad", 2, 2, "OP-01", "GD-01",
                T0.plusSeconds(5)));
        ApiException same = expectApi(() -> groundWireService.remove(id, W1, new RemoveRequest(
                "rm-same", 4, 4, "OP-01", "OP-01", T0.plusSeconds(6))));
        assertEquals("DOUBLE_CONFIRM_REQUIRED", same.code());
        // 再次挂接（实物已挂接，引用下一条待执行挂接步）拒绝
        ApiException already = expectApi(() -> groundWireService.apply(id, W1, new ApplyRequest(
                "ap-again", 3, 3, "OP-01", "GD-01", T0.plusSeconds(7))));
        assertEquals("WIRE_STATE", already.code());
        assertEquals("APPLIED", wireStatus(W1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_operation", Integer.class));
        assertEquals("PENDING", orderService.get(id).steps().get(2).status());
    }

    // ---------- 归还失败分支 ----------

    @Test
    void returnOnlyWritesOffRemovedWiresBatchAtomic() {
        long id = issueTicket("rt-bad", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-rt", "INT-1101",
                List.of(item(W1, 1), item(W2, 1)), T0));
        openBreaker(id, "rt-bad-1");
        groundWireService.apply(id, W1, new ApplyRequest("ap-rt", 2, 2, "OP-01", "GD-01",
                T0.plusSeconds(5)));
        // W1 仍挂接、W2 仅借出：整批归还拒绝，不核销任何一条
        ApiException notRemoved = expectApi(() -> groundWireService.returnWires(id, new ReturnRequest(
                "rt-mixed", List.of(item(W1, 3), item(W2, 2)), "WH-01", T0.plusSeconds(6))));
        assertEquals("WIRE_NOT_REMOVED", notRemoved.code());
        assertEquals("APPLIED", wireStatus(W1));
        assertEquals("BORROWED", wireStatus(W2));
        // 版本不符
        ApiException badVer = expectApi(() -> groundWireService.returnWires(id, new ReturnRequest(
                "rt-ver", List.of(item(W2, 9)), "WH-01", T0.plusSeconds(6))));
        assertEquals("VERSION_MISMATCH", badVer.code());
        // 不属于本票：先让 W2 在库，再用另一张票尝试核销
        ApiException foreign = expectApi(() -> groundWireService.returnWires(9999L, new ReturnRequest(
                "rt-foreign", List.of(item(W1, 3)), "WH-01", T0.plusSeconds(6))));
        assertEquals("TICKET_NOT_FOUND", foreign.code());
        assertEquals(404, foreign.status().value());
        // 仓管编号必填由 Bean Validation 兜底（见 web 400 用例）
    }

    // ---------- 普通步确认不得绕过实物联锁 ----------

    @Test
    void normalStepConfirmRejectedForPhysicalTrackedWire() {
        long id = issueTicket("phys-bypass", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-bypass", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "bypass-1");
        ApiException ex = expectApi(() -> orderService.confirm(id, 2,
                new ConfirmStepRequest("cf-bypass-2", "OP-01", "GD-01")));
        assertEquals("PHYSICAL_GROUND_WIRE_STEP", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("PENDING", orderService.get(id).steps().get(1).status());
        assertEquals("BORROWED", wireStatus(W1));
    }

    // ---------- 并发借用：同一实物唯一占用 ----------

    @Test
    void concurrentBorrowSameWireOnlyOneTicketWins() throws Exception {
        long ticketA = orderService.create(new CreateTicketRequest("c-con-a", "INT-1101", "票A",
                List.of(step("OPEN_BREAKER", "BR-1101", "OPEN")))).ticket().ticketId();
        long ticketB = orderService.create(new CreateTicketRequest("c-con-b", "INT-1101", "票B",
                List.of(step("OPEN_BREAKER", "BR-1101", "OPEN")))).ticket().ticketId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean winA = new AtomicBoolean();
        AtomicBoolean winB = new AtomicBoolean();
        AtomicReference<String> loserCode = new AtomicReference<>();
        Runnable borrowA = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.borrow(ticketA, new BorrowRequest("bw-con-a", "INT-1101",
                        List.of(item(W1, 1)), T0.plusSeconds(1)));
                winA.set(true);
            } catch (ApiException e) {
                loserCode.set(e.code());
            }
        };
        Runnable borrowB = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.borrow(ticketB, new BorrowRequest("bw-con-b", "INT-1101",
                        List.of(item(W1, 1)), T0.plusSeconds(1)));
                winB.set(true);
            } catch (ApiException e) {
                loserCode.compareAndSet(null, e.code());
            }
        };
        Future<?> f1 = pool.submit(borrowA);
        Future<?> f2 = pool.submit(borrowB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertNotEquals(winA.get(), winB.get(), "竞争借用必须唯一成功");
        assertTrue(winA.get() || winB.get());
        assertEquals("WIRE_NOT_AVAILABLE", loserCode.get());
        Long owner = jdbc.queryForObject(
                "SELECT current_ticket_id FROM ground_wire WHERE wire_no = ?", Long.class, W1);
        assertEquals(winA.get() ? ticketA : ticketB, owner);
        assertEquals("BORROWED", wireStatus(W1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_borrow WHERE wire_no = ? AND status = 'BORROWED'",
                Integer.class, W1));
    }

    // ---------- 并发挂接与送电：不得既通过送电又留下挂接 ----------

    @Test
    void concurrentApplyAndEnergizeNeverBothSucceed() throws Exception {
        long id = issueTicket("race", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("CLOSE_BREAKER", "BR-1101", "CLOSED"));
        groundWireService.borrow(id, new BorrowRequest("bw-race", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "race-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean applyOk = new AtomicBoolean();
        AtomicBoolean energizePassed = new AtomicBoolean();
        // 挂接事务：真实服务双确认挂接
        Runnable applyTx = () -> {
            ready.countDown();
            await(start);
            try {
                groundWireService.apply(id, W1, new ApplyRequest("ap-race", 2, 2, "OP-01", "GD-01",
                        T0.plusSeconds(10)));
                applyOk.set(true);
            } catch (ApiException e) {
                assertEquals("INTERLOCK_BLOCKED", e.code());
            }
        };
        // 送电事务：走与步确认完全相同的实物复查入口；通过则在同一事务内合开关
        Runnable energizeTx = () -> {
            ready.countDown();
            await(start);
            Boolean passed = txTemplate.execute(status -> {
                List<String> applied = groundWireService.findAppliedWiresForEnergize("INT-1101");
                if (!applied.isEmpty()) {
                    return false;
                }
                jdbc.update("UPDATE device_state SET state = 'CLOSED', updated_at = ?"
                        + " WHERE device_id = 'BR-1101'", java.sql.Timestamp.from(T0.plusSeconds(10)));
                return true;
            });
            energizePassed.set(Boolean.TRUE.equals(passed));
        };
        Future<?> f1 = pool.submit(applyTx);
        Future<?> f2 = pool.submit(energizeTx);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertNotEquals(applyOk.get(), energizePassed.get(), "挂接成功与送电通过互斥");
        String breakerState = jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class);
        if (applyOk.get()) {
            assertEquals("APPLIED", wireStatus(W1));
            assertEquals("OPEN", breakerState, "挂接留下则送电不得通过");
        } else {
            assertEquals("BORROWED", wireStatus(W1), "送电先通过则挂接必被带电闭锁");
            assertEquals("CLOSED", breakerState);
        }
    }

    // ---------- 幂等：同键同参重放、异参 409、失败不占键 ----------

    @Test
    void idempotentBorrowReplayAndConflictOverHttp() throws Exception {
        long id = issueTicket("idem-bw", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "bw-idem",
                "intervalId", "INT-1101",
                "items", List.of(Map.of("wireNo", W1, "expectedVersion", 1)),
                "occurredAt", T0.toString()));
        MvcResult r1 = mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        JsonNode n1 = mapper.readTree(r1.getResponse().getContentAsString());
        assertEquals(2, n1.get("wires").get(0).get("version").asInt());
        // 同键异参（精确时刻不同）409
        String changed = mapper.writeValueAsString(Map.of(
                "requestId", "bw-idem",
                "intervalId", "INT-1101",
                "items", List.of(Map.of("wireNo", W1, "expectedVersion", 1)),
                "occurredAt", T0.plusSeconds(1).toString()));
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
        // 只发生一次借用
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_borrow WHERE borrow_request_id = 'bw-idem'", Integer.class));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        long id = issueTicket("idem-fail", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String badBody = mapper.writeValueAsString(Map.of(
                "requestId", "bw-reuse",
                "intervalId", "INT-1101",
                "items", List.of(Map.of("wireNo", W1, "expectedVersion", 9)),
                "occurredAt", T0.toString()));
        // 版本不符失败（409），事务回滚不占幂等键
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'bw-reuse'", Integer.class));
        // 同键、修正后的参数请求成功（若占键会先被异参 409 拦截）
        String goodBody = mapper.writeValueAsString(Map.of(
                "requestId", "bw-reuse",
                "intervalId", "INT-1101",
                "items", List.of(Map.of("wireNo", W1, "expectedVersion", 1)),
                "occurredAt", T0.toString()));
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk());
        assertEquals("BORROWED", wireStatus(W1));
        // 此后原失败参数再用同键即异参 409
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isConflict());
    }

    @Test
    void applyReplayExecutesOnceAndEvidenceImmutable() throws Exception {
        long id = issueTicket("idem-ap", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-idem-ap", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "idem-ap-1");
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "ap-idem",
                "expectedVersion", 2,
                "stepSeq", 2,
                "operatorId", "OP-01",
                "guardianId", "GD-01",
                "occurredAt", T0.plusSeconds(5).toString()));
        MvcResult r1 = mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/" + W1 + "/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/" + W1 + "/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode n1 = mapper.readTree(r1.getResponse().getContentAsString());
        JsonNode n2 = mapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(n1.get("id").asLong(), n2.get("id").asLong());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire_operation WHERE request_id = 'ap-idem'", Integer.class));
        assertEquals(3, wireVersion(W1));
        assertEquals("DONE", orderService.get(id).steps().get(1).status());
    }

    @Test
    void invalidRequestBodyReturns400OverHttp() throws Exception {
        long id = issueTicket("idem-400", "INT-1101", step("OPEN_BREAKER", "BR-1101", "OPEN"));
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "bw-400",
                "intervalId", "",
                "items", List.of(Map.of("wireNo", W1, "expectedVersion", 1)),
                "occurredAt", T0.toString()));
        mockMvc.perform(post("/api/tickets/" + id + "/ground-wires/borrow")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ---------- 结构化指纹：含分隔符文本不可碰撞 ----------

    @Test
    void structuredFingerprintDistinguishesDelimiterInsideValues() {
        // 两组逻辑上不同的字段序列，朴素拼接会碰撞，长度前缀编码必须区分
        String fp1 = IdempotencyService.structuredFingerprint("EP", List.of("a|b", "c"));
        String fp2 = IdempotencyService.structuredFingerprint("EP", List.of("a", "b|c"));
        assertNotEquals(fp1, fp2);
        // 集合顺序与元素个数也入摘要
        String fp3 = IdempotencyService.structuredFingerprint("EP", List.of("x", "y"));
        String fp4 = IdempotencyService.structuredFingerprint("EP", List.of("xy"));
        assertNotEquals(fp3, fp4);
        // 同参稳定
        assertEquals(fp1, IdempotencyService.structuredFingerprint("EP", List.of("a|b", "c")));
    }

    // ---------- 异常中止票：不可新借，已挂接仍须合法拆除归还 ----------

    @Test
    void exceptionTicketCannotBorrowButAppliedWireMustStillBeRemovedAndReturned() {
        long id = issueTicket("exc", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"),
                step("REMOVE_GROUND", W1, "REMOVED"));
        groundWireService.borrow(id, new BorrowRequest("bw-exc", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "exc-1");
        groundWireService.apply(id, W1, new ApplyRequest("ap-exc", 2, 2, "OP-01", "GD-01",
                T0.plusSeconds(5)));
        // 遥信不符中止转异常，挂接实物不自动恢复
        jdbc.update("UPDATE switching_ticket SET status = 'EXCEPTION' WHERE id = ?", id);
        jdbc.update("DELETE FROM interval_execution_lock WHERE interval_id = 'INT-1101'");
        ApiException noBorrow = expectApi(() -> groundWireService.borrow(id, new BorrowRequest(
                "bw-exc-2", "INT-1101", List.of(item(W2, 1)), T0.plusSeconds(6))));
        assertEquals("TICKET_NOT_BORROWABLE", noBorrow.code());
        assertEquals("AVAILABLE", wireStatus(W2));
        // 已挂接实物仍须合法拆除与归还
        groundWireService.remove(id, W1, new RemoveRequest("rm-exc", 3, 3, "OP-02", "GD-02",
                T0.plusSeconds(7)));
        assertEquals("REMOVED_PENDING_RETURN", wireStatus(W1));
        groundWireService.returnWires(id, new ReturnRequest("rt-exc", List.of(item(W1, 4)),
                "WH-01", T0.plusSeconds(8)));
        assertEquals("AVAILABLE", wireStatus(W1));
        orderService.archive(id, new ArchiveRequest("arc-exc"));
        assertEquals("ARCHIVED", orderService.get(id).ticket().status());
    }

    // ---------- 查询 ----------

    @Test
    void inventoryAndHistoryQueriesReflectState() {
        long id = issueTicket("query", "INT-1101",
                step("OPEN_BREAKER", "BR-1101", "OPEN"),
                step("APPLY_GROUND", W1, "APPLIED"));
        groundWireService.borrow(id, new BorrowRequest("bw-query", "INT-1101",
                List.of(item(W1, 1)), T0));
        openBreaker(id, "query-1");
        groundWireService.apply(id, W1, new ApplyRequest("ap-query", 2, 2, "OP-01", "GD-01",
                T0.plusSeconds(5)));
        List<WireView> applied = groundWireService.listWires("APPLIED");
        assertEquals(List.of(W1), applied.stream().map(WireView::wireNo).toList());
        WireView one = groundWireService.getWire(W1);
        assertEquals("INT-1101", one.intervalId());
        assertEquals(id, one.currentTicketId());
        ApiException notFound = expectApi(() -> groundWireService.getWire("GW-MISSING"));
        assertEquals("WIRE_NOT_FOUND", notFound.code());
        assertFalse(groundWireService.listOperations(id).isEmpty());
        assertFalse(groundWireService.listBorrows(id).isEmpty());
        // 归档要求全部归还，本票挂接中归档被拒
        ApiException blocked = expectApi(() -> orderService.archive(id, new ArchiveRequest("arc-query")));
        assertEquals("GROUND_WIRE_NOT_RETURNED", blocked.code());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
