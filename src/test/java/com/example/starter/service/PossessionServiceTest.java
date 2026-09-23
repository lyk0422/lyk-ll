package com.example.starter.service;

import com.example.starter.api.dto.ConflictView;
import com.example.starter.api.dto.CreateRequestRequest;
import com.example.starter.api.dto.OperationRequest;
import com.example.starter.api.dto.OperationResultView;
import com.example.starter.api.dto.RequestView;
import com.example.starter.api.dto.SnapshotView;
import com.example.starter.domain.RequestStatus;
import com.example.starter.exception.BusinessRuleException;
import com.example.starter.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 封锁窗核心规则的 H2（MODE=MySQL）集成测试：半开端点、缓冲冲突、原子审批、
 * 并发争抢、紧急抢占失败回滚、终态幂等与快照不变。
 */
@SpringBootTest
class PossessionServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private PossessionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM possession_event");
        jdbc.update("DELETE FROM possession_section");
        jdbc.update("DELETE FROM possession_request");
        jdbc.update("DELETE FROM rail_section");
        jdbc.update("DELETE FROM operation_ledger");
        jdbc.update("DELETE FROM plan_snapshot");
    }

    private CreateRequestRequest req(String id, List<String> sections,
                                     Instant start, Instant end,
                                     long pre, long post, int priority) {
        return new CreateRequestRequest(id, sections, start, end, pre, post, priority,
                start.minusSeconds(3600));
    }

    private OperationRequest op(int expectedVersion, Instant businessTime) {
        return new OperationRequest(expectedVersion, businessTime);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RequestView createAndApprove(String id, String section,
                                         Instant start, Instant end, int priority) {
        service.create(req(id, List.of(section), start, end, 0, 0, priority));
        service.approve(id, op(1, T0.plusSeconds(100)));
        return service.get(id);
    }

    // ------------------------------------------------------------------
    // 半开端点
    // ------------------------------------------------------------------

    @Test
    void halfOpen_endpointTouching_doesNotConflict() {
        createAndApprove("A", "S1", T0, T0.plus(1, ChronoUnit.HOURS), 10);
        service.create(req("B", List.of("S1"), T0.plus(1, ChronoUnit.HOURS),
                T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));

        List<ConflictView> conflicts = service.checkConflicts("B");
        assertTrue(conflicts.isEmpty(), "端点相接 [..,11:00) 与 [11:00,..) 不得冲突");

        OperationResultView result = service.approve("B", op(1, T0.plusSeconds(101)));
        assertEquals(RequestStatus.APPROVED.name(), result.status());
    }

    @Test
    void positiveLengthOverlap_conflictsAndRejects() {
        createAndApprove("A", "S1", T0, T0.plus(1, ChronoUnit.HOURS), 10);
        service.create(req("B", List.of("S1"), T0.plus(59, ChronoUnit.MINUTES),
                T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));

        List<ConflictView> conflicts = service.checkConflicts("B");
        assertEquals(1, conflicts.size());
        assertEquals("A", conflicts.get(0).conflictingRequestId());
        assertEquals(T0.plus(59, ChronoUnit.MINUTES), conflicts.get(0).overlapStart());
        assertEquals(T0.plus(1, ChronoUnit.HOURS), conflicts.get(0).overlapEnd());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.approve("B", op(1, T0.plusSeconds(102))));
        assertEquals(1, ex.getConflicts().size());
        assertEquals(RequestStatus.PENDING.name(), service.get("B").status(),
                "冲突拒绝不得部分批准，B 仍为 PENDING");
    }

    // ------------------------------------------------------------------
    // 安全缓冲
    // ------------------------------------------------------------------

    @Test
    void postBuffer_extendsOccupationAndConflicts_rawWindowsOnlyTouching() {
        // A 原始窗 [10:00,11:00)，后置缓冲 15 分钟 → 占用至 11:15
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 900, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));

        // B 原始窗 11:10 开始，与 A 原始窗端点不重叠，但落在 A 的安全缓冲内
        service.create(req("B", List.of("S1"), T0.plus(70, ChronoUnit.MINUTES),
                T0.plus(3, ChronoUnit.HOURS), 0, 0, 10));
        assertEquals(1, service.checkConflicts("B").size());
        assertThrows(ConflictException.class, () -> service.approve("B", op(1, T0.plusSeconds(103))));

        // C 11:15 整点开始：与缓冲后占用窗端点相接，不冲突
        service.create(req("C", List.of("S1"), T0.plus(75, ChronoUnit.MINUTES),
                T0.plus(4, ChronoUnit.HOURS), 0, 0, 10));
        assertTrue(service.checkConflicts("C").isEmpty());
        assertEquals(RequestStatus.APPROVED.name(),
                service.approve("C", op(1, T0.plusSeconds(104))).status());
    }

    @Test
    void preBuffer_conflictsWhenBufferReachesPriorWindow() {
        // B 11:00 开始、前置缓冲 30 分钟 → 占用从 10:30 起，与 A [10:00,11:00) 正长度重叠
        createAndApprove("A", "S1", T0, T0.plus(1, ChronoUnit.HOURS), 10);
        service.create(req("B", List.of("S1"), T0.plus(1, ChronoUnit.HOURS),
                T0.plus(2, ChronoUnit.HOURS), 1800, 0, 10));
        List<ConflictView> conflicts = service.checkConflicts("B");
        assertEquals(1, conflicts.size());
        assertEquals(T0.plus(30, ChronoUnit.MINUTES), conflicts.get(0).overlapStart());
    }

    // ------------------------------------------------------------------
    // 原子审批 / 冲突排序
    // ------------------------------------------------------------------

    @Test
    void atomicApproval_oneConflictingSectionRejectsWholeRequest() {
        createAndApprove("A", "S1", T0, T0.plus(1, ChronoUnit.HOURS), 10);

        service.create(req("B", List.of("S2", "S1"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 10));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.approve("B", op(1, T0.plusSeconds(110))));

        assertEquals(RequestStatus.PENDING.name(), service.get("B").status(),
                "多区段申请必须原子审批，冲突时全部区段都不得批准");
        assertEquals(1, ex.getConflicts().size());
        assertEquals("S1", ex.getConflicts().get(0).sectionId());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id='B' AND event_type='APPROVED'",
                Integer.class), "冲突回滚不得写入 APPROVED 事件");
    }

    @Test
    void conflicts_sortedBySectionThenTime() {
        createAndApprove("A1", "S2", T0, T0.plus(1, ChronoUnit.HOURS), 10);
        createAndApprove("A2", "S1", T0.plus(2, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 10);
        createAndApprove("A3", "S1", T0, T0.plus(1, ChronoUnit.HOURS), 10);

        service.create(req("B", List.of("S1", "S2"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(150, ChronoUnit.MINUTES), 0, 0, 10));
        List<ConflictView> conflicts = service.checkConflicts("B");

        assertEquals(List.of("S1", "S1", "S2"), conflicts.stream().map(ConflictView::sectionId).toList());
        assertEquals(List.of("A3", "A2", "A1"),
                conflicts.stream().map(ConflictView::conflictingRequestId).toList());
    }

    // ------------------------------------------------------------------
    // 并发争抢
    // ------------------------------------------------------------------

    @Test
    void concurrentApproval_sameSection_onlyOneSucceeds() throws Exception {
        service.create(req("A", List.of("S1"), T0, T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));
        service.create(req("B", List.of("S1"), T0.plus(1, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 0, 0, 10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitBarrier(barrier);
                service.approve("A", op(1, T0.plusSeconds(200)));
            });
            Future<?> f2 = pool.submit(() -> {
                awaitBarrier(barrier);
                service.approve("B", op(1, T0.plusSeconds(201)));
            });

            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (Future<?> f : List.of(f1, f2)) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    success.incrementAndGet();
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    assertTrue(cause instanceof ConflictException, "冲突方应收到 ConflictException");
                    rejected.incrementAndGet();
                }
            }
            assertEquals(1, success.get(), "同一区段并发审批只能有一个成功");
            assertEquals(1, rejected.get());
        } finally {
            pool.shutdownNow();
        }

        long approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_request WHERE status='APPROVED'", Long.class);
        long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_request WHERE status='PENDING'", Long.class);
        assertEquals(1, approved);
        assertEquals(1, pending);
    }

    @Test
    void concurrentApproval_disjointSections_bothSucceed() throws Exception {
        service.create(req("A", List.of("S1"), T0, T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));
        service.create(req("B", List.of("S2"), T0.plus(1, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 0, 0, 10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitBarrier(barrier);
                service.approve("A", op(1, T0.plusSeconds(210)));
            });
            Future<?> f2 = pool.submit(() -> {
                awaitBarrier(barrier);
                service.approve("B", op(1, T0.plusSeconds(211)));
            });
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(RequestStatus.APPROVED.name(), service.get("A").status());
        assertEquals(RequestStatus.APPROVED.name(), service.get("B").status());
    }

    // ------------------------------------------------------------------
    // 紧急抢占
    // ------------------------------------------------------------------

    @Test
    void emergency_preemptsAllLowerPriorityApprovedWindowsAtomically() {
        createAndApprove("A", "S1", T0, T0.plus(2, ChronoUnit.HOURS), 10);
        createAndApprove("B", "S2", T0.plus(1, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 5);
        service.create(req("E", List.of("S1", "S2"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 20));

        OperationResultView result = service.approve("E", op(1, T0.plusSeconds(300)));
        assertEquals(RequestStatus.APPROVED.name(), result.status());
        assertEquals(List.of("A", "B"), result.preemptedRequestIds());
        assertEquals(2, result.preemptedConflicts().size());

        assertEquals(RequestStatus.PREEMPTED.name(), service.get("A").status());
        assertEquals(3, service.get("A").version());
        assertEquals(RequestStatus.PREEMPTED.name(), service.get("B").status());
        assertEquals(3, service.get("B").version());
        assertEquals(RequestStatus.APPROVED.name(), service.get("E").status());
        assertEquals(2, service.get("E").version());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id='A' AND event_type='PREEMPTED'",
                Integer.class));
    }

    @Test
    void emergency_againstActiveWindow_rejectsAndRollsBackEverything() {
        service.create(req("A", List.of("S1"), T0, T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));
        service.activate("A", op(2, T0.plusSeconds(200)));

        // B 是可抢占的低优先级 APPROVED 窗口，但 A 已激活 → 只能部分抢占，整笔拒绝
        createAndApprove("B", "S2", T0.plus(1, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 10);
        service.create(req("E", List.of("S1", "S2"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 20));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.approve("E", op(1, T0.plusSeconds(300))));
        assertEquals(2, ex.getConflicts().size());

        assertEquals(RequestStatus.ACTIVE.name(), service.get("A").status());
        assertEquals(RequestStatus.APPROVED.name(), service.get("B").status(),
                "整笔回滚：可抢占的 B 也不得被置为 PREEMPTED");
        assertEquals(RequestStatus.PENDING.name(), service.get("E").status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE event_type='PREEMPTED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id='E'", Integer.class),
                "E 只应有 SUBMITTED 事件");
    }

    @Test
    void emergency_againstSameOrHigherPriority_rejects() {
        createAndApprove("A", "S1", T0, T0.plus(2, ChronoUnit.HOURS), 20);
        service.create(req("E", List.of("S1"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 20));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.approve("E", op(1, T0.plusSeconds(301))));
        assertTrue(ex.getMessage().contains("同级或更高优先级"));
        assertEquals(RequestStatus.APPROVED.name(), service.get("A").status());
        assertEquals(RequestStatus.PENDING.name(), service.get("E").status());
    }

    @Test
    void normalRequest_againstConflicts_neverPreempts() {
        createAndApprove("A", "S1", T0, T0.plus(2, ChronoUnit.HOURS), 5);
        service.create(req("N", List.of("S1"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 10));
        assertThrows(ConflictException.class, () -> service.approve("N", op(1, T0.plusSeconds(302))));
        assertEquals(RequestStatus.APPROVED.name(), service.get("A").status());
    }

    // ------------------------------------------------------------------
    // 合法迁移 / 版本
    // ------------------------------------------------------------------

    @Test
    void lifecycle_legalTransitionsAndVersionBumps() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        assertEquals(1, service.get("A").version());

        assertEquals(2, service.approve("A", op(1, T0.plusSeconds(100))).version());
        assertEquals(3, service.activate("A", op(2, T0.plusSeconds(200))).version());
        assertEquals(4, service.complete("A", op(3, T0.plusSeconds(300))).version());
        assertEquals(RequestStatus.COMPLETED.name(), service.get("A").status());

        assertThrows(ConflictException.class,
                () -> service.complete("A", op(4, T0.plusSeconds(301))),
                "COMPLETED 终态后异参复用请求键应 409");
        assertThrows(BusinessRuleException.class,
                () -> service.cancel("A", op(4, T0.plusSeconds(302))),
                "已完成不可取消（CANCEL 无成功账本，走状态迁移校验）");
    }

    @Test
    void activate_fromPending_rejected() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        assertThrows(BusinessRuleException.class, () -> service.activate("A", op(1, T0.plusSeconds(100))));
    }

    @Test
    void cancel_approvedThenConflictingEmergencyStillRejectedAsPreempted() {
        service.create(req("A", List.of("S1"), T0, T0.plus(2, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));
        assertEquals(RequestStatus.CANCELLED.name(),
                service.cancel("A", op(2, T0.plusSeconds(150))).status());

        service.create(req("E", List.of("S1"), T0.plus(30, ChronoUnit.MINUTES),
                T0.plus(90, ChronoUnit.MINUTES), 0, 0, 20));
        assertTrue(service.checkConflicts("E").isEmpty(), "CANCELLED 为终态，不再参与冲突");
        assertEquals(RequestStatus.APPROVED.name(), service.approve("E", op(1, T0.plusSeconds(300))).status());
    }

    @Test
    void wrongExpectedVersion_rejected() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        assertThrows(BusinessRuleException.class,
                () -> service.approve("A", op(9, T0.plusSeconds(100))));
        assertEquals(RequestStatus.PENDING.name(), service.get("A").status());
    }

    // ------------------------------------------------------------------
    // 终态幂等
    // ------------------------------------------------------------------

    @Test
    void terminalCompletion_sameParams_replaysFirstResultWithoutExtraEvent() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));
        service.activate("A", op(2, T0.plusSeconds(200)));
        OperationResultView first = service.complete("A", op(3, T0.plusSeconds(300)));
        int eventsAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id='A'", Integer.class);

        OperationResultView replay = service.complete("A", op(3, T0.plusSeconds(300)));
        assertTrue(replay.replayed());
        assertEquals(first.version(), replay.version());
        assertEquals(first.status(), replay.status());
        assertEquals(eventsAfterFirst, jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id='A'", Integer.class),
                "同参重复操作不得追加事件");

        OperationResultView replay2 = service.complete("A", op(3, T0.plusSeconds(300)));
        assertTrue(replay2.replayed());
    }

    @Test
    void idempotentKey_differentParams_returns409() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));

        // 已成功完成后，携带不同 expectedVersion 复用同一请求键 → 409
        service.activate("A", op(2, T0.plusSeconds(200)));
        service.complete("A", op(3, T0.plusSeconds(300)));
        assertThrows(ConflictException.class,
                () -> service.complete("A", op(4, T0.plusSeconds(300))),
                "异参复用请求键必须 409，即使新参数恰好也指向终态");

        // approve 同参重放
        OperationResultView replayed = service.approve("A", op(1, T0.plusSeconds(100)));
        assertTrue(replayed.replayed());
        assertEquals(RequestStatus.APPROVED.name(), replayed.status());
        // approve 异参 409
        assertThrows(ConflictException.class,
                () -> service.approve("A", op(1, T0.plusSeconds(999))));
    }

    // ------------------------------------------------------------------
    // 快照不可变
    // ------------------------------------------------------------------

    @Test
    void snapshot_publishedContentNeverChanges() {
        service.create(req("A", List.of("S1"), T0, T0.plus(1, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("A", op(1, T0.plusSeconds(100)));
        service.create(req("B", List.of("S2"), T0.plus(2, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS), 0, 0, 10));
        service.approve("B", op(1, T0.plusSeconds(101)));

        SnapshotView snapshot = service.publishSnapshot(T0.plusSeconds(400));
        assertEquals(2, snapshot.requests().size());
        assertEquals(RequestStatus.APPROVED.name(),
                snapshot.requests().stream().filter(r -> r.requestId().equals("A")).findFirst().orElseThrow()
                        .status());

        // 发布后状态继续流转
        service.activate("A", op(2, T0.plusSeconds(500)));
        service.complete("A", op(3, T0.plusSeconds(600)));
        service.cancel("B", op(2, T0.plusSeconds(700)));
        // 新申请出现
        service.create(req("C", List.of("S3"), T0.plus(5, ChronoUnit.HOURS),
                T0.plus(6, ChronoUnit.HOURS), 0, 0, 10));

        SnapshotView refetched = service.getSnapshot(snapshot.snapshotId());
        assertEquals(snapshot.snapshotId(), refetched.snapshotId());
        assertEquals(2, refetched.requests().size(), "快照内容不随后续变化增减申请");
        assertEquals(RequestStatus.APPROVED.name(),
                refetched.requests().stream().filter(r -> r.requestId().equals("A")).findFirst().orElseThrow()
                        .status(), "快照旧读：A 仍停留在发布时的 APPROVED");
        assertEquals(RequestStatus.APPROVED.name(),
                refetched.requests().stream().filter(r -> r.requestId().equals("B")).findFirst().orElseThrow()
                        .status(), "快照旧读：B 仍停留在发布时的 APPROVED");
    }
}
