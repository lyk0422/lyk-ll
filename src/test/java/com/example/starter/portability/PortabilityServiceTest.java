package com.example.starter.portability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.portability.repo.EffectiveRecordRepository;
import com.example.starter.portability.service.PortabilityService;
import com.example.starter.portability.web.ApplyRequest;
import com.example.starter.portability.web.BroadcastRequest;
import com.example.starter.portability.web.BroadcastView;
import com.example.starter.portability.web.ConfirmRequest;
import com.example.starter.portability.web.EventView;
import com.example.starter.portability.web.FlowView;

/**
 * 携号转网核心流转测试：资格边界、授权码失效、生效窗口、幂等重放、并发唯一在途与撤销/生效竞态。
 * 使用独立 H2 内存库（MySQL 兼容模式）与可控时钟，每个用例前清空数据。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class PortabilityServiceTest {

    private static final Instant T0 = TestClockConfiguration.T0;
    private static final String MSISDN = "13800000001";

    @Autowired
    private PortabilityService service;
    @Autowired
    private EffectiveRecordRepository effectiveRepo;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM flow_event");
        jdbc.update("DELETE FROM effective_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM portability_flow");
        clock.setInstant(T0);
    }

    private ApplyRequest eligibleRequest(String requestId, String msisdn) {
        return new ApplyRequest(requestId, msisdn, "OP-A", "OP-B", false, null, null);
    }

    private FlowView applyConfirmed(String requestId, String msisdn) {
        FlowView view = service.apply(eligibleRequest(requestId, msisdn));
        return service.confirm(view.flowId(), new ConfirmRequest(view.authCode()));
    }

    @Test
    void applySuccessIssuesAuthCodeValid24Hours() {
        FlowView view = service.apply(eligibleRequest("req-1", MSISDN));

        assertEquals(FlowState.AUTH_ISSUED, view.state());
        assertNotNull(view.authCode());
        assertEquals(T0, view.authIssuedAt());
        assertEquals(T0.plus(24, ChronoUnit.HOURS), view.authExpiresAt());

        List<EventView> events = service.listEvents(view.flowId());
        assertEquals(List.of("FLOW_APPLIED", "AUTH_CODE_ISSUED"),
                events.stream().map(EventView::eventType).toList());
    }

    @Test
    void applyRejectsUnpaidBill() {
        ApplyRequest request = new ApplyRequest("req-2", MSISDN, "OP-A", "OP-B", true, null, null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(request));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals("ELIGIBILITY_UNPAID_BILL", e.code());
    }

    @Test
    void applyRejectsActiveContract() {
        LocalDate futureEnd = LocalDate.of(2026, 10, 1);
        ApplyRequest request = new ApplyRequest("req-3", MSISDN, "OP-A", "OP-B", false, futureEnd, null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(request));
        assertEquals("ELIGIBILITY_IN_CONTRACT", e.code());
    }

    @Test
    void applyEligibilityExactly120DaysBoundary() {
        // 恰满 120 日：允许
        ApplyRequest ok = new ApplyRequest("req-4a", MSISDN, "OP-A", "OP-B", false, null,
                T0.minus(120, ChronoUnit.DAYS));
        assertEquals(FlowState.AUTH_ISSUED, service.apply(ok).state());

        // 差 1 秒未满 120 日：拒绝
        ApplyRequest notYet = new ApplyRequest("req-4b", "13800000002", "OP-A", "OP-B", false, null,
                T0.minus(120, ChronoUnit.DAYS).plus(1, ChronoUnit.SECONDS));
        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(notYet));
        assertEquals("ELIGIBILITY_PORT_INTERVAL", e.code());
    }

    @Test
    void applyIdempotencyReplayAndConflict() {
        ApplyRequest request = eligibleRequest("req-5", MSISDN);
        FlowView first = service.apply(request);
        FlowView replayed = service.apply(request);
        assertEquals(first, replayed);

        ApplyRequest tampered = new ApplyRequest("req-5", MSISDN, "OP-A", "OP-C", false, null, null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(tampered));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        // 冲突不改变原流程状态
        assertEquals(FlowState.AUTH_ISSUED, service.getFlow(first.flowId()).state());
    }

    @Test
    void concurrentApplySameNumberOnlyOneInFlight() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable task = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.apply(eligibleRequest("req-6-" + Thread.currentThread().getId(), MSISDN));
                success.incrementAndGet();
            } catch (BusinessException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                assertEquals("NUMBER_IN_FLIGHT", e.code());
                conflict.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, success.get());
        assertEquals(1, conflict.get());
        Long inFlight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portability_flow WHERE msisdn = ? AND active_msisdn IS NOT NULL",
                Long.class, MSISDN);
        assertEquals(1L, inFlight);
    }

    @Test
    void confirmRejectsWrongCodeAndSucceedsWithRightCode() {
        FlowView view = service.apply(eligibleRequest("req-7", MSISDN));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(view.flowId(), new ConfirmRequest("WRONG-CODE")));
        assertEquals("AUTH_CODE_MISMATCH", e.code());

        FlowView confirmed = service.confirm(view.flowId(), new ConfirmRequest(view.authCode()));
        assertEquals(FlowState.CONFIRMED, confirmed.state());
    }

    @Test
    void authCodeExpiresExactlyAt24Hours() {
        FlowView first = service.apply(eligibleRequest("req-8a", MSISDN));
        // 恰满 24 小时：已失效
        clock.setInstant(T0.plus(24, ChronoUnit.HOURS));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(first.flowId(), new ConfirmRequest(first.authCode())));
        assertEquals("AUTH_CODE_EXPIRED", e.code());

        // 失效前 1 秒：仍可确认
        FlowView second = service.apply(eligibleRequest("req-8b", "13800000002"));
        clock.setInstant(T0.plus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.SECONDS));
        assertEquals(FlowState.CONFIRMED,
                service.confirm(second.flowId(), new ConfirmRequest(second.authCode())).state());
    }

    @Test
    void broadcastOutsideWindowAtExact0400KeepsPending() {
        FlowView confirmed = applyConfirmed("req-9", MSISDN);
        // 04:00 整点视为窗口外
        clock.setInstant(Instant.parse("2026-09-26T04:00:00Z"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-9")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals("OUTSIDE_EFFECTIVE_WINDOW", e.code());
        assertEquals(FlowState.CONFIRMED, service.getFlow(confirmed.flowId()).state());

        // 下一窗口 02:00 内可生效
        clock.setInstant(Instant.parse("2026-09-27T02:00:00Z"));
        BroadcastView view = service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-9"));
        assertEquals(FlowState.EFFECTIVE, view.state());
        assertEquals(1, effectiveRepo.countByFlowId(confirmed.flowId()));
    }

    @Test
    void broadcastBeforeConfirmRejected() {
        FlowView view = service.apply(eligibleRequest("req-10", MSISDN));
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(view.flowId(), new BroadcastRequest("bc-10")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("INVALID_STATE", e.code());
    }

    @Test
    void broadcastIdempotentReplayProducesSingleEffectiveRecord() {
        FlowView confirmed = applyConfirmed("req-11", MSISDN);
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));

        BroadcastView first = service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-11"));
        BroadcastView replayed = service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-11"));
        assertEquals(first, replayed);
        assertEquals(1, effectiveRepo.countByFlowId(confirmed.flowId()));

        // 同键异参（同一 requestId 指向另一流程）判 409
        FlowView other = applyConfirmed("req-11b", "13800000002");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(other.flowId(), new BroadcastRequest("bc-11")));
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(FlowState.CONFIRMED, service.getFlow(other.flowId()).state());
    }

    @Test
    void lateConfirmAfterEffectiveDoesNotChangeResult() {
        FlowView confirmed = applyConfirmed("req-12", MSISDN);
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-12"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(confirmed.flowId(), new ConfirmRequest("ANY")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("FLOW_TERMINATED", e.code());
        assertEquals(FlowState.EFFECTIVE, service.getFlow(confirmed.flowId()).state());
    }

    @Test
    void cancelIsTerminalAndBlocksConfirmAndBroadcast() {
        FlowView view = service.apply(eligibleRequest("req-13", MSISDN));
        FlowView cancelled = service.cancel(view.flowId());
        assertEquals(FlowState.CANCELLED, cancelled.state());

        BusinessException confirmError = assertThrows(BusinessException.class,
                () -> service.confirm(view.flowId(), new ConfirmRequest(view.authCode())));
        assertEquals("FLOW_TERMINATED", confirmError.code());

        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        BusinessException broadcastError = assertThrows(BusinessException.class,
                () -> service.broadcast(view.flowId(), new BroadcastRequest("bc-13")));
        assertEquals("FLOW_CANCELLED", broadcastError.code());

        // 撤销后号码释放，可重新申请
        assertEquals(FlowState.AUTH_ISSUED,
                service.apply(eligibleRequest("req-13b", MSISDN)).state());
    }

    @Test
    void cancelAfterEffectiveRejected() {
        FlowView confirmed = applyConfirmed("req-14", MSISDN);
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-14"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.cancel(confirmed.flowId()));
        assertEquals("ALREADY_EFFECTIVE", e.code());
        assertEquals(FlowState.EFFECTIVE, service.getFlow(confirmed.flowId()).state());
    }

    @Test
    void cancelAndBroadcastRaceYieldUniqueTerminalState() throws Exception {
        FlowView confirmed = applyConfirmed("req-15", MSISDN);
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger effective = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable cancelTask = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.cancel(confirmed.flowId());
                cancelled.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable broadcastTask = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-15"));
                effective.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> f1 = pool.submit(cancelTask);
        Future<?> f2 = pool.submit(broadcastTask);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 唯一终态：恰一个成功，另一个被拒
        assertEquals(1, cancelled.get() + effective.get());
        assertEquals(1, rejected.get());
        FlowState finalState = service.getFlow(confirmed.flowId()).state();
        assertTrue(finalState.isTerminal());
        assertEquals(effective.get() == 1, finalState == FlowState.EFFECTIVE);
        assertEquals(effective.get(), effectiveRepo.countByFlowId(confirmed.flowId()));
    }

    @Test
    void eventsAreOrderedAcrossStages() {
        FlowView confirmed = applyConfirmed("req-16", MSISDN);
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        service.broadcast(confirmed.flowId(), new BroadcastRequest("bc-16"));

        List<EventView> events = service.listEvents(confirmed.flowId());
        assertEquals(List.of("FLOW_APPLIED", "AUTH_CODE_ISSUED", "DONOR_CONFIRMED", "BROADCAST_EFFECTIVE"),
                events.stream().map(EventView::eventType).toList());
        List<Long> ids = events.stream().map(EventView::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }
}
