package com.example.starter.continuation;

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

import com.example.starter.continuation.ContinuationDtos.ApplyContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.EffectContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ReviewContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.WithdrawContinuationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.ground.GroundWireDtos.AttachWireRequest;
import com.example.starter.ground.GroundWireDtos.BorrowWiresRequest;
import com.example.starter.ground.GroundWireDtos.DetachWireRequest;
import com.example.starter.ground.GroundWireDtos.EvidenceView;
import com.example.starter.ground.GroundWireDtos.LoanView;
import com.example.starter.ground.GroundWireDtos.ReturnWiresRequest;
import com.example.starter.ground.GroundWireDtos.WireStepResult;
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
import com.example.starter.ticket.TicketDtos.StepView;
import com.example.starter.ticket.TicketDtos.TicketDetailView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 余步续票集成测试：真实 H2（MODE=MySQL）验证剩余步骤冻结、双人审核、
 * 生效原子转移间隔执行权与接地线责任接续、唯一生效后继并发竞争、
 * 继承线拦送电、撤回边界、单据版本与 requestId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ContinuationIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T01:00:00Z");

    /** 原票（挂线场景）剩余步骤：拆接地线 → 合开关送电。 */
    private static final List<StepSpec> REMAINING_GROUND = List.of(
            new StepSpec("REMOVE_GROUND", "GW-1101", "REMOVED"),
            new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"));
    /** 原票（无挂线场景）剩余步骤：合开关送电。 */
    private static final List<StepSpec> REMAINING_CLOSE = List.of(
            new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"));

    @Autowired
    private ContinuationService continuationService;
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
        jdbc.update("DELETE FROM ground_wire_inheritance");
        jdbc.update("DELETE FROM continuation_succession");
        jdbc.update("DELETE FROM continuation_order");
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

    /** 构造一张异常中止原票：断开关→挂接地线（实物已挂）→余拆线、送电两步未做。 */
    private long exceptionTicketWithAppliedWire(String p) {
        long id = orderService.create(new CreateTicketRequest(
                "c-" + p, "INT-1101", "挂线检修票", List.of(
                        new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"),
                        new StepSpec("APPLY_GROUND", "GW-1101", "APPLIED"),
                        new StepSpec("REMOVE_GROUND", "GW-1101", "REMOVED"),
                        new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-" + p, "REV-01"));
        orderService.issue(id, new IssueRequest("iss-" + p));
        orderService.confirm(id, 1, new ConfirmStepRequest("cf1-" + p, "OP-01", "GD-01"));
        groundService.borrow(id, new BorrowWiresRequest("b-" + p, id, 1L, List.of("JDX-1001"), T1));
        groundService.attach(id, new AttachWireRequest("a-" + p, id, 2L, 2, "JDX-1001", "OP-01", "GD-01", T1));
        orderService.confirm(id, 2, new ConfirmStepRequest("cf2-" + p, "OP-01", "GD-01"));
        // 遥信不符：挂接地线步回报已拆除，原票中止转异常并释放间隔锁
        String result = orderService.processTelemetry(id, "GW-1101", "REMOVED");
        assertEquals("ABORTED", result);
        assertEquals("EXCEPTION", orderService.get(id).ticket().status());
        return id;
    }

    /** 构造一张异常中止原票（无实物）：断开关已做，余合开关送电一步。 */
    private long exceptionTicketNoWire(String p) {
        long id = orderService.create(new CreateTicketRequest(
                "c-" + p, "INT-1101", "停送电票", List.of(
                        new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"),
                        new StepSpec("CLOSE_BREAKER", "BR-1101", "CLOSED"))))
                .ticket().ticketId();
        orderService.review(id, new ReviewRequest("rv-" + p, "REV-01"));
        orderService.issue(id, new IssueRequest("iss-" + p));
        orderService.confirm(id, 1, new ConfirmStepRequest("cf1-" + p, "OP-01", "GD-01"));
        String result = orderService.processTelemetry(id, "BR-1101", "CLOSED");
        assertEquals("ABORTED", result);
        return id;
    }

    /** 申请并完成双人审核，返回 REVIEWED 状态单据（版本3）。 */
    private ContinuationView applyAndReview(long originalId, String p, List<StepSpec> steps) {
        ContinuationView doc = continuationService.apply(
                new ApplyContinuationRequest("ap-" + p, originalId, "INT-1101", "余步续票", steps));
        continuationService.review(doc.continuationId(), new ReviewContinuationRequest("rva-" + p, 1L, "REV-A"));
        return continuationService.review(doc.continuationId(),
                new ReviewContinuationRequest("rvb-" + p, 2L, "REV-B"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---------- 主流程：冻结→双审→生效→续做→合法拆线归还→归档 ----------

    @Test
    void fullContinuationFlowTransfersResponsibilityAndArchives() {
        long original = exceptionTicketWithAppliedWire("m");
        ContinuationView applied = continuationService.apply(
                new ApplyContinuationRequest("ap-m", original, "INT-1101", "余步续票", REMAINING_GROUND));
        assertEquals("APPLIED", applied.status());
        assertEquals(1, applied.version());
        long newId = applied.newTicketId();
        // 余步冻结：新票步序沿用原票剩余步序3/4，全部待执行，已执行步骤与证据不复制
        TicketDetailView newDetail = orderService.get(newId);
        assertEquals(List.of(3, 4), newDetail.steps().stream().map(StepView::seq).toList());
        assertTrue(newDetail.steps().stream().allMatch(s -> "PENDING".equals(s.status())));
        assertEquals("DRAFT", newDetail.ticket().status());
        // 原票保持中止，实物占用不变
        assertEquals("EXCEPTION", orderService.get(original).ticket().status());
        assertEquals(original, groundService.getWire("JDX-1001").wire().occupiedByTicket());

        continuationService.review(applied.continuationId(), new ReviewContinuationRequest("rva-m", 1L, "REV-A"));
        ContinuationView reviewed = continuationService.review(applied.continuationId(),
                new ReviewContinuationRequest("rvb-m", 2L, "REV-B"));
        assertEquals("REVIEWED", reviewed.status());
        assertEquals(3, reviewed.version());

        ContinuationView effective = continuationService.effect(applied.continuationId(),
                new EffectContinuationRequest("ef-m", 3L));
        assertEquals("EFFECTIVE", effective.status());
        assertEquals(4, effective.version());
        assertEquals(List.of("JDX-1001"), effective.inheritedWires());
        // 新票按原签发有效期规则生效：自生效起24小时（UTC）
        TicketDetailView issued = orderService.get(newId);
        assertEquals("ISSUED", issued.ticket().status());
        assertEquals(T0, issued.ticket().issuedAt());
        assertEquals(T0.plus(Duration.ofHours(24)), issued.ticket().expiresAt());
        // 生效原子把间隔执行权交给续票
        assertEquals(newId, orderService.activeTicketForInterval("INT-1101"));
        // 原票因继承登记中止归档
        assertEquals("ARCHIVED", orderService.get(original).ticket().status());
        // 责任接续：实物占用转给续票，编号不改，版本加1
        WireView wire = groundService.getWire("JDX-1001").wire();
        assertEquals(newId, wire.occupiedByTicket());
        assertEquals("APPLIED", wire.status());
        assertEquals(4, wire.version());

        // 续做剩余步骤：拆线步确认后，续票合法拆除本继承链实物（双人确认）
        orderService.confirm(newId, 3, new ConfirmStepRequest("cf-m3", "OP-01", "GD-01"));
        WireStepResult detached = groundService.detach(newId, new DetachWireRequest(
                "dt-m", newId, 4L, 3, "JDX-1001", "OP-01", "GD-01", T1));
        assertEquals("REMOVED_PENDING_RETURN", detached.wireStatus());
        // 送电步：实物已拆除，间隔复查通过
        orderService.confirm(newId, 4, new ConfirmStepRequest("cf-m4", "OP-01", "GD-01"));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        // 归还：仓管核对编号，核销的是原票借用行，原借用历史不改
        groundService.returnWires(newId, new ReturnWiresRequest(
                "rt-m", newId, 5L, List.of("JDX-1001"), "KEEPER-01", T1));
        LoanView loan = groundService.getWire("JDX-1001").loans().get(0);
        assertEquals(original, loan.ticketId());
        assertEquals("RETURNED", loan.status());
        assertEquals("KEEPER-01", loan.keeperId());
        assertEquals("AVAILABLE", groundService.getWire("JDX-1001").wire().status());
        // 续票最终归档：继承实物全部核销后放行，间隔锁释放
        orderService.archive(newId, new ArchiveRequest("arc-m"));
        assertEquals("ARCHIVED", orderService.get(newId).ticket().status());
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        // 历史证据：原票挂接证据保留可查，续票拆除证据独立追加，不复制不覆盖
        assertEquals(List.of("APPLIED"), groundService.ticketGround(original).evidences().stream()
                .map(EvidenceView::action).toList());
        assertEquals(List.of("REMOVED"), groundService.ticketGround(newId).evidences().stream()
                .map(EvidenceView::action).toList());
    }

    // ---------- 余步冻结：跨间隔、删改剩余步骤、无剩余、原票状态 ----------

    @Test
    void applyRejectsCrossIntervalModifiedStepsNoRemainingAndBadOriginalState() {
        long original = exceptionTicketWithAppliedWire("fz");
        // 跨间隔拒绝
        ApiException cross = assertThrows(ApiException.class, () -> continuationService.apply(
                new ApplyContinuationRequest("ap-fz1", original, "INT-9999", "余步续票", REMAINING_GROUND)));
        assertEquals("CROSS_INTERVAL", cross.code());
        assertEquals(422, cross.status().value());
        // 篡改剩余步骤预期状态拒绝
        List<StepSpec> modified = List.of(
                new StepSpec("REMOVE_GROUND", "GW-1101", "REMOVED"),
                new StepSpec("CLOSE_BREAKER", "BR-1101", "OPEN"));
        ApiException tampered = assertThrows(ApiException.class, () -> continuationService.apply(
                new ApplyContinuationRequest("ap-fz2", original, "INT-1101", "余步续票", modified)));
        assertEquals("STEPS_MODIFIED", tampered.code());
        // 删除剩余步骤拒绝
        ApiException deleted = assertThrows(ApiException.class, () -> continuationService.apply(
                new ApplyContinuationRequest("ap-fz3", original, "INT-1101", "余步续票", REMAINING_CLOSE)));
        assertEquals("STEPS_MODIFIED", deleted.code());
        // 原票非异常/作废状态拒绝
        long draft = orderService.create(new CreateTicketRequest(
                "c-fz4", "INT-1102", "草稿票", List.of(new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        ApiException state = assertThrows(ApiException.class, () -> continuationService.apply(
                new ApplyContinuationRequest("ap-fz4", draft, "INT-1102", "余步续票",
                        List.of(new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN")))));
        assertEquals("ORIGINAL_STATE", state.code());
        // 无剩余步骤拒绝
        long done = orderService.create(new CreateTicketRequest(
                "c-fz5", "INT-1101", "已完成票", List.of(new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        orderService.review(done, new ReviewRequest("rv-fz5", "REV-01"));
        orderService.issue(done, new IssueRequest("iss-fz5"));
        orderService.confirm(done, 1, new ConfirmStepRequest("cf-fz5", "OP-01", "GD-01"));
        assertEquals("ABORTED", orderService.processTelemetry(done, "BR-1101", "CLOSED"));
        ApiException empty = assertThrows(ApiException.class, () -> continuationService.apply(
                new ApplyContinuationRequest("ap-fz5", done, "INT-1101", "余步续票",
                        List.of(new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN")))));
        assertEquals("NO_REMAINING_STEPS", empty.code());
        // 全部拒绝：无任何续票单据与新票产生
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_order", Integer.class));
    }

    // ---------- 双人审核与生效前提 ----------

    @Test
    void reviewRequiresTwoDifferentReviewersAndEffectRequiresReviewed() {
        long original = exceptionTicketNoWire("dr");
        ContinuationView doc = continuationService.apply(
                new ApplyContinuationRequest("ap-dr", original, "INT-1101", "余步续票", REMAINING_CLOSE));
        continuationService.review(doc.continuationId(), new ReviewContinuationRequest("rva-dr", 1L, "REV-A"));
        // 同一审核人不能确认两次
        ApiException same = assertThrows(ApiException.class, () -> continuationService.review(
                doc.continuationId(), new ReviewContinuationRequest("rvb-dr", 2L, "REV-A")));
        assertEquals("DOUBLE_REVIEW_REQUIRED", same.code());
        assertEquals(400, same.status().value());
        // 未双审完成不能生效
        ApiException eff = assertThrows(ApiException.class, () -> continuationService.effect(
                doc.continuationId(), new EffectContinuationRequest("ef-dr", 2L)));
        assertEquals("CONTINUATION_STATE", eff.code());
        assertEquals(409, eff.status().value());
        // 第二名不同审核人确认后双审完成
        ContinuationView reviewed = continuationService.review(doc.continuationId(),
                new ReviewContinuationRequest("rvc-dr", 2L, "REV-B"));
        assertEquals("REVIEWED", reviewed.status());
        assertEquals("REV-A", reviewed.reviewerA());
        assertEquals("REV-B", reviewed.reviewerB());
    }

    // ---------- 单据版本：旧版409，成功加1 ----------

    @Test
    void staleVersionRejected409AndSuccessIncrements() {
        long original = exceptionTicketNoWire("ver");
        ContinuationView doc = continuationService.apply(
                new ApplyContinuationRequest("ap-ver", original, "INT-1101", "余步续票", REMAINING_CLOSE));
        assertEquals(1, doc.version());
        ApiException stale = assertThrows(ApiException.class, () -> continuationService.review(
                doc.continuationId(), new ReviewContinuationRequest("rv-ver9", 9L, "REV-A")));
        assertEquals("VERSION_MISMATCH", stale.code());
        assertEquals(409, stale.status().value());
        ContinuationView after = continuationService.review(doc.continuationId(),
                new ReviewContinuationRequest("rv-ver1", 1L, "REV-A"));
        assertEquals(2, after.version());
        // 旧版本再次提交仍409
        ApiException staleAgain = assertThrows(ApiException.class, () -> continuationService.review(
                doc.continuationId(), new ReviewContinuationRequest("rv-ver1b", 1L, "REV-B")));
        assertEquals("VERSION_MISMATCH", staleAgain.code());
    }

    // ---------- 签发复核：原票版本变化拒绝 ----------

    @Test
    void effectRechecksOriginalTicketVersion() {
        long original = exceptionTicketNoWire("ov");
        ContinuationView doc = applyAndReview(original, "ov", REMAINING_CLOSE);
        // 申请后原票被改动（版本变化），签发复核拒绝
        jdbc.update("UPDATE switching_ticket SET version = version + 1 WHERE id = ?", original);
        ApiException ex = assertThrows(ApiException.class, () -> continuationService.effect(
                doc.continuationId(), new EffectContinuationRequest("ef-ov", 3L)));
        assertEquals("ORIGINAL_TICKET_CHANGED", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals("REVIEWED", continuationService.get(doc.continuationId()).status());
        assertEquals("EXCEPTION", orderService.get(original).ticket().status());
    }

    // ---------- 间隔执行权：第三票持锁时生效拒绝，不释放给第三票 ----------

    @Test
    void effectRejectedWhenThirdTicketHoldsInterval() {
        long original = exceptionTicketNoWire("tp");
        ContinuationView doc = applyAndReview(original, "tp", REMAINING_CLOSE);
        // 第三票经正常流程占用间隔执行权
        long third = orderService.create(new CreateTicketRequest(
                "c-tp3", "INT-1101", "第三票", List.of(new StepSpec("OPEN_BREAKER", "BR-1101", "OPEN"))))
                .ticket().ticketId();
        orderService.review(third, new ReviewRequest("rv-tp3", "REV-09"));
        orderService.issue(third, new IssueRequest("iss-tp3"));
        ApiException ex = assertThrows(ApiException.class, () -> continuationService.effect(
                doc.continuationId(), new EffectContinuationRequest("ef-tp", 3L)));
        assertEquals("CONCURRENT_TICKET", ex.code());
        assertEquals(409, ex.status().value());
        // 整事务回滚：无后继登记，续票未生效，原票未归档，间隔仍属第三票
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_succession", Integer.class));
        assertEquals("REVIEWED", continuationService.get(doc.continuationId()).status());
        assertEquals("EXCEPTION", orderService.get(original).ticket().status());
        assertEquals(third, orderService.activeTicketForInterval("INT-1101"));
    }

    // ---------- 双继承竞争：两份续票同时批准只一个后继 ----------

    @Test
    void concurrentEffectOnlyOneSuccessor() throws Exception {
        long original = exceptionTicketNoWire("race");
        ContinuationView docA = applyAndReview(original, "ra", REMAINING_CLOSE);
        ContinuationView docB = applyAndReview(original, "rb", REMAINING_CLOSE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Long> winnerTicket = new AtomicReference<>();
        Runnable effectA = () -> {
            ready.countDown();
            await(start);
            try {
                continuationService.effect(docA.continuationId(), new EffectContinuationRequest("ef-ra", 3L));
                succeeded.incrementAndGet();
                winnerTicket.set(docA.newTicketId());
            } catch (ApiException e) {
                assertEquals(409, e.status().value());
                rejected.incrementAndGet();
            }
        };
        Runnable effectB = () -> {
            ready.countDown();
            await(start);
            try {
                continuationService.effect(docB.continuationId(), new EffectContinuationRequest("ef-rb", 3L));
                succeeded.incrementAndGet();
                winnerTicket.set(docB.newTicketId());
            } catch (ApiException e) {
                assertEquals(409, e.status().value());
                rejected.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(effectA);
        Future<?> f2 = pool.submit(effectB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 只一个生效后继：一个成功一个409， succession 仅一行，间隔锁归胜者
        assertEquals(1, succeeded.get());
        assertEquals(1, rejected.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_succession", Integer.class));
        assertEquals(winnerTicket.get(), orderService.activeTicketForInterval("INT-1101"));
        assertEquals("ARCHIVED", orderService.get(original).ticket().status());
        int effectiveCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM continuation_order WHERE status = 'EFFECTIVE'", Integer.class);
        assertEquals(1, effectiveCount);
        assertEquals("REVIEWED", jdbc.queryForObject(
                "SELECT status FROM continuation_order WHERE id = ? AND status <> 'EFFECTIVE'",
                String.class, winnerTicket.get() == docA.newTicketId() ? docB.continuationId() : docA.continuationId()));
    }

    // ---------- 继承线拦送电：送电查整个间隔全部已挂接线 ----------

    @Test
    void inheritedAppliedWireBlocksEnergizeUntilLawfulDetach() {
        long original = exceptionTicketWithAppliedWire("blk");
        ContinuationView doc = applyAndReview(original, "blk", REMAINING_GROUND);
        continuationService.effect(doc.continuationId(), new EffectContinuationRequest("ef-blk", 3L));
        long newId = doc.newTicketId();
        // 拆线步确认：遥信快照置已拆，但继承的实物仍挂接
        orderService.confirm(newId, 3, new ConfirmStepRequest("cf-blk3", "OP-01", "GD-01"));
        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'GW-1101'", String.class));
        assertEquals("APPLIED", groundService.getWire("JDX-1001").wire().status());
        // 送电步被拦：不只看新票新借线，整个间隔继承来的已挂接线同样拦截
        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.confirm(newId, 4, new ConfirmStepRequest("cf-blk4", "OP-01", "GD-01")));
        assertEquals("GROUND_WIRE_STILL_APPLIED", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals("PENDING", orderService.get(newId).steps().get(1).status());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
        assertEquals("GROUNDED_ENERGIZE", jdbc.queryForObject(
                "SELECT rule_code FROM interlock_hit WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class, newId));
        // 合法拆除并归还继承实物后，送电放行
        groundService.detach(newId, new DetachWireRequest("dt-blk", newId, 4L, 3, "JDX-1001", "OP-01", "GD-01", T1));
        orderService.confirm(newId, 4, new ConfirmStepRequest("cf-blk5", "OP-01", "GD-01"));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT state FROM device_state WHERE device_id = 'BR-1101'", String.class));
    }

    // ---------- 撤回：未生效可撤回不释放原间隔或实物，已生效不能撤回 ----------

    @Test
    void withdrawBeforeEffectKeepsOriginalIntervalAndWires() {
        long original = exceptionTicketWithAppliedWire("wd");
        ContinuationView doc = continuationService.apply(
                new ApplyContinuationRequest("ap-wd", original, "INT-1101", "余步续票", REMAINING_GROUND));
        ContinuationView withdrawn = continuationService.withdraw(doc.continuationId(),
                new WithdrawContinuationRequest("wd-w", 1L));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(2, withdrawn.version());
        // 不释放原间隔或实物：原票保持中止，实物仍挂接且占用不变，无责任接续，无间隔锁
        assertEquals("EXCEPTION", orderService.get(original).ticket().status());
        WireView wire = groundService.getWire("JDX-1001").wire();
        assertEquals("APPLIED", wire.status());
        assertEquals(original, wire.occupiedByTicket());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ground_wire_inheritance", Integer.class));
        assertNull(orderService.activeTicketForInterval("INT-1101"));
        // 撤回后不能再生效
        ApiException ex = assertThrows(ApiException.class, () -> continuationService.effect(
                doc.continuationId(), new EffectContinuationRequest("wd-e", 2L)));
        assertEquals("CONTINUATION_STATE", ex.code());
    }

    @Test
    void effectiveContinuationCannotWithdraw() {
        long original = exceptionTicketNoWire("we");
        ContinuationView doc = applyAndReview(original, "we", REMAINING_CLOSE);
        continuationService.effect(doc.continuationId(), new EffectContinuationRequest("ef-we", 3L));
        ApiException ex = assertThrows(ApiException.class, () -> continuationService.withdraw(
                doc.continuationId(), new WithdrawContinuationRequest("we-w", 4L)));
        assertEquals("CONTINUATION_EFFECTIVE", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals("EFFECTIVE", continuationService.get(doc.continuationId()).status());
    }

    // ---------- 不得代管其他票的线 ----------

    @Test
    void continuationCannotCustodyOtherTicketsWires() {
        long original = exceptionTicketWithAppliedWire("nc");
        ContinuationView doc = applyAndReview(original, "nc", REMAINING_GROUND);
        continuationService.effect(doc.continuationId(), new EffectContinuationRequest("ef-nc", 3L));
        long newId = doc.newTicketId();
        // 其他票借用 JDX-1002（与继承链无关）
        long other = orderService.create(new CreateTicketRequest(
                "c-nc2", "INT-1102", "他票", List.of(new StepSpec("OPEN_BREAKER", "BR-F1", "OPEN"))))
                .ticket().ticketId();
        groundService.borrow(other, new BorrowWiresRequest("b-nc2", other, 1L, List.of("JDX-1002"), T1));
        // 续票拆除他票的线：非本票及本继承链实物，拒绝
        ApiException detach = assertThrows(ApiException.class, () -> groundService.detach(newId,
                new DetachWireRequest("dt-nc", newId, 2L, 3, "JDX-1002", "OP-01", "GD-01", T1)));
        assertEquals("WIRE_NOT_APPLIED", detach.code());
        // 续票归还他票的线：仓管核对拒绝核销
        ApiException ret = assertThrows(ApiException.class, () -> groundService.returnWires(newId,
                new ReturnWiresRequest("rt-nc", newId, 2L, List.of("JDX-1002"), "KEEPER-01", T1)));
        assertEquals("WIRE_NOT_BORROWED_BY_TICKET", ret.code());
        assertEquals(other, groundService.getWire("JDX-1002").wire().occupiedByTicket());
    }

    // ---------- 原票保持中止：不重开、不推进已做步骤；续票新票不走普通通道 ----------

    @Test
    void originalStaysAbortedAndContinuationTicketSkipsNormalChannel() {
        long original = exceptionTicketWithAppliedWire("st");
        ContinuationView doc = applyAndReview(original, "st", REMAINING_GROUND);
        // 续票新票不能走普通审核/预发通道
        ApiException bound = assertThrows(ApiException.class,
                () -> orderService.review(doc.newTicketId(), new ReviewRequest("rv-st9", "REV-09")));
        assertEquals("CONTINUATION_BOUND", bound.code());
        continuationService.effect(doc.continuationId(), new EffectContinuationRequest("ef-st", 3L));
        // 原票登记中止归档后：不能重开或推进任何步骤
        ApiException advance = assertThrows(ApiException.class, () -> orderService.confirm(
                original, 3, new ConfirmStepRequest("cf-st3", "OP-01", "GD-01")));
        assertEquals("TICKET_STATE", advance.code());
        // 已做步骤保持原样不回退
        TicketDetailView originalDetail = orderService.get(original);
        assertEquals("DONE", originalDetail.steps().get(0).status());
        assertEquals("DONE", originalDetail.steps().get(1).status());
        assertEquals("ARCHIVED", originalDetail.ticket().status());
    }

    // ---------- 幂等：同键同参重放，异参409，失败不占键 ----------

    @Test
    void idempotentApplyReplayMismatchAndFailureNotOccupyingKey() throws Exception {
        long original = exceptionTicketNoWire("idem");
        String body = mapper.writeValueAsString(Map.of(
                "requestId", "ap-idem",
                "originalTicketId", original,
                "intervalId", "INT-1101",
                "title", "余步续票",
                "steps", List.of(Map.of("operationType", "CLOSE_BREAKER",
                        "deviceId", "BR-1101", "expectedState", "CLOSED"))));
        MvcResult r1 = mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        // 只申请一次：一张单据、一张新票
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_order", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM switching_ticket", Integer.class));

        // 同键异参409
        String changed = mapper.writeValueAsString(Map.of(
                "requestId", "ap-idem",
                "originalTicketId", original,
                "intervalId", "INT-1101",
                "title", "改名续票",
                "steps", List.of(Map.of("operationType", "CLOSE_BREAKER",
                        "deviceId", "BR-1101", "expectedState", "CLOSED"))));
        MvcResult mismatch = mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(mismatch.getResponse().getContentAsString().contains("IDEMPOTENT_MISMATCH"));

        // 失败不占键：跨间隔被拒后，同键改正参数可成功
        String bad = mapper.writeValueAsString(Map.of(
                "requestId", "ap-idem-fail",
                "originalTicketId", original,
                "intervalId", "INT-9999",
                "title", "余步续票",
                "steps", List.of(Map.of("operationType", "CLOSE_BREAKER",
                        "deviceId", "BR-1101", "expectedState", "CLOSED"))));
        mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity());
        String good = mapper.writeValueAsString(Map.of(
                "requestId", "ap-idem-fail",
                "originalTicketId", original,
                "intervalId", "INT-1101",
                "title", "余步续票",
                "steps", List.of(Map.of("operationType", "CLOSE_BREAKER",
                        "deviceId", "BR-1101", "expectedState", "CLOSED"))));
        mockMvc.perform(post("/api/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM continuation_order", Integer.class));
    }
}
