package com.example.starter.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.orchestrator.dto.ConditionSpec;
import com.example.starter.orchestrator.dto.EventView;
import com.example.starter.orchestrator.dto.NodeSpec;
import com.example.starter.orchestrator.dto.NodeView;
import com.example.starter.orchestrator.dto.PlanSubmitRequest;
import com.example.starter.orchestrator.dto.PlanView;

/**
 * 编排服务 H2 集成测试：覆盖主流程、循环检测、幂等提交、失败补偿、
 * 条件跳过、幂等取消、并发提交与计划恢复。
 */
@SpringBootTest
class OrchestratorServiceTest {

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private OrchestratorService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM launch_event");
        jdbc.update("DELETE FROM launch_node");
        jdbc.update("DELETE FROM launch_plan");
    }

    @AfterAll
    static void shutdown(@Autowired JdbcTemplate jdbc) {
        // 释放 DB_CLOSE_DELAY=-1 保留的命名内存库
        jdbc.execute("SHUTDOWN");
    }

    private static NodeSpec node(String key, String healthCheck, List<String> deps, String compensation) {
        return new NodeSpec(key, Map.of("ns", "/demo"), 0, null, deps, healthCheck, compensation);
    }

    private static PlanSubmitRequest request(String planId, String requestId, NodeSpec... nodes) {
        return new PlanSubmitRequest(planId, 1, requestId, List.of(nodes));
    }

    private static Map<String, NodeView> index(PlanView view) {
        return view.nodes().stream().collect(Collectors.toMap(NodeView::key, Function.identity()));
    }

    @Test
    void executeHappyPathStartsNodesInDependencyOrder() {
        service.submit(request("p1", "r1",
                node("a", "PASS", List.of(), null),
                node("b", "PASS", List.of("a"), null),
                node("c", "PASS", List.of("b"), null)));

        PlanView view = service.execute("p1");

        assertEquals("SUCCEEDED", view.status());
        assertTrue(view.nodes().stream().allMatch(n -> n.state().equals("HEALTHY")));
        List<EventView> events = service.getEvents("p1");
        List<String> started = events.stream()
                .filter(e -> e.eventType().equals("NODE_STARTED")).map(EventView::nodeKey).toList();
        assertEquals(List.of("a", "b", "c"), started);
        assertEquals("PLAN_SUCCEEDED", events.get(events.size() - 1).eventType());
    }

    @Test
    void duplicateSubmitWithSameParamsIsIdempotent() {
        PlanSubmitRequest req = request("p2", "r2", node("a", "PASS", List.of(), null));
        PlanView first = service.submit(req);
        PlanView second = service.submit(req);

        assertFalse(first.idempotentReplay());
        assertTrue(second.idempotentReplay());
        assertEquals(first.planId(), second.planId());
        Integer plans = jdbc.queryForObject("SELECT COUNT(*) FROM launch_plan", Integer.class);
        assertEquals(1, plans);
        assertEquals(1, service.getEvents("p2").stream()
                .filter(e -> e.eventType().equals("PLAN_SUBMITTED")).count());
    }

    @Test
    void duplicateSubmitWithDifferentParamsConflicts() {
        service.submit(request("p3", "r3", node("a", "PASS", List.of(), null)));

        PlanSubmitRequest changed = request("p3", "r3",
                node("a", "PASS", List.of(), null), node("b", "PASS", List.of("a"), null));
        assertThrows(PlanConflictException.class, () -> service.submit(changed));
    }

    @Test
    void explicitCycleIsRejectedWithFullPath() {
        PlanSubmitRequest req = request("p4", "r4",
                node("a", "PASS", List.of("c"), null),
                node("b", "PASS", List.of("a"), null),
                node("c", "PASS", List.of("b"), null));

        CycleDependencyException e = assertThrows(CycleDependencyException.class, () -> service.submit(req));
        List<String> path = e.getCyclePath();
        assertEquals(path.get(0), path.get(path.size() - 1));
        assertEquals(4, path.size());
        assertTrue(path.containsAll(List.of("a", "b", "c")));
        Integer plans = jdbc.queryForObject("SELECT COUNT(*) FROM launch_plan", Integer.class);
        assertEquals(0, plans);
    }

    @Test
    void implicitCycleViaConditionReferenceIsRejected() {
        PlanSubmitRequest req = request("p5", "r5",
                new NodeSpec("a", null, 0, new ConditionSpec("b", "HEALTHY"), List.of(), "PASS", null),
                new NodeSpec("b", null, 0, new ConditionSpec("a", "HEALTHY"), List.of(), "PASS", null));

        CycleDependencyException e = assertThrows(CycleDependencyException.class, () -> service.submit(req));
        assertEquals(e.getCyclePath().get(0), e.getCyclePath().get(e.getCyclePath().size() - 1));
    }

    @Test
    void healthCheckFailureCompensatesStartedNodesInReverseDependencyOrder() {
        service.submit(request("p6", "r6",
                node("a", "PASS", List.of(), "stopA"),
                node("b", "PASS", List.of("a"), "stopB"),
                node("c", "FAIL", List.of("b"), null),
                node("d", "PASS", List.of("c"), null)));

        PlanView view = service.execute("p6");

        assertEquals("FAILED", view.status());
        Map<String, NodeView> nodes = index(view);
        assertEquals("COMPENSATED", nodes.get("a").state());
        assertEquals("COMPENSATED", nodes.get("b").state());
        assertEquals("FAILED", nodes.get("c").state());
        assertEquals("BLOCKED", nodes.get("d").state());
        List<String> compensated = service.getEvents("p6").stream()
                .filter(e -> e.eventType().equals("COMPENSATION_EXECUTED"))
                .map(EventView::nodeKey).toList();
        assertEquals(List.of("b", "a"), compensated);
    }

    @Test
    void unsatisfiedConditionSkipsNodeAndBlocksDependents() {
        service.submit(request("p7", "r7",
                node("a", "PASS", List.of(), null),
                new NodeSpec("b", null, 0, new ConditionSpec("a", "FAILED"), List.of(), "PASS", null),
                node("c", "PASS", List.of("b"), null)));

        PlanView view = service.execute("p7");

        Map<String, NodeView> nodes = index(view);
        assertEquals("HEALTHY", nodes.get("a").state());
        assertEquals("SKIPPED", nodes.get("b").state());
        assertEquals("BLOCKED", nodes.get("c").state());
        assertEquals("FAILED", view.status());
    }

    @Test
    void cancelIsIdempotentAndCompensatesStartedNodesOnlyOnce() throws Exception {
        service.submit(request("p8", "r8",
                node("a", "PASS", List.of(), "stopA"),
                new NodeSpec("b", null, 1500, null, List.of("a"), "PASS", null)));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> running = pool.submit(() -> service.execute("p8"));
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (service.getEvents("p8").stream()
                        .anyMatch(e -> e.eventType().equals("NODE_STARTED") && "b".equals(e.nodeKey()))) {
                    break;
                }
                Thread.sleep(20);
            }
            PlanView cancelled = service.cancel("p8");
            assertEquals("CANCELLED", cancelled.status());
            running.get(10, TimeUnit.SECONDS);

            PlanView view = service.getPlan("p8");
            assertEquals("CANCELLED", view.status());
            Map<String, NodeView> nodes = index(view);
            assertEquals("CANCELLED", nodes.get("a").state());
            assertTrue(nodes.get("a").compensated());
            assertEquals("CANCELLED", nodes.get("b").state());

            PlanView replay = service.cancel("p8");
            assertTrue(replay.idempotentReplay());
            List<EventView> events = service.getEvents("p8");
            assertEquals(1, events.stream()
                    .filter(e -> e.eventType().equals("COMPENSATION_EXECUTED") && "a".equals(e.nodeKey())).count());
            assertEquals(1, events.stream()
                    .filter(e -> e.eventType().equals("NODE_CANCELLED") && "a".equals(e.nodeKey())).count());
            assertEquals(1, events.stream()
                    .filter(e -> e.eventType().equals("PLAN_CANCELLED")).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelBeforeExecuteMarksPendingNodesCancelledWithoutCompensation() {
        service.submit(request("p9", "r9",
                node("a", "PASS", List.of(), "stopA"),
                node("b", "PASS", List.of("a"), null)));

        PlanView view = service.cancel("p9");

        assertEquals("CANCELLED", view.status());
        assertTrue(view.nodes().stream().allMatch(n -> n.state().equals("CANCELLED")));
        assertTrue(view.nodes().stream().noneMatch(NodeView::compensated));
        assertEquals(0, service.getEvents("p9").stream()
                .filter(e -> e.eventType().equals("COMPENSATION_EXECUTED")).count());
    }

    @Test
    void resumeDoesNotRestartConfirmedHealthyNodes() {
        service.submit(request("p10", "r10",
                node("a", "PASS", List.of(), null),
                node("b", "PASS", List.of("a"), null)));
        // 模拟同一 JVM 内崩溃遗留：计划执行中，a 已确认成功，b 未启动
        jdbc.update("UPDATE launch_plan SET status = 'RUNNING' WHERE plan_id = 'p10'");
        jdbc.update("UPDATE launch_node SET state = 'HEALTHY' WHERE plan_id = 'p10' AND node_key = 'a'");

        PlanView view = service.resume("p10");

        assertEquals("SUCCEEDED", view.status());
        List<EventView> events = service.getEvents("p10");
        assertEquals(0, events.stream()
                .filter(e -> e.eventType().equals("NODE_STARTED") && "a".equals(e.nodeKey())).count());
        assertEquals(1, events.stream()
                .filter(e -> e.eventType().equals("NODE_STARTED") && "b".equals(e.nodeKey())).count());
    }

    @Test
    void resumeAfterFailureDoesNotRepeatCompletedCompensation() {
        service.submit(request("p11", "r11",
                node("a", "PASS", List.of(), "stopA"),
                node("b", "FAIL", List.of("a"), null)));
        service.execute("p11");
        assertEquals(1, service.getEvents("p11").stream()
                .filter(e -> e.eventType().equals("COMPENSATION_EXECUTED")).count());

        PlanView view = service.resume("p11");

        assertEquals("FAILED", view.status());
        List<EventView> events = service.getEvents("p11");
        assertEquals(1, events.stream()
                .filter(e -> e.eventType().equals("COMPENSATION_EXECUTED")).count());
        assertEquals(1, events.stream()
                .filter(e -> e.eventType().equals("NODE_STARTED") && "a".equals(e.nodeKey())).count());
    }

    @Test
    void concurrentDuplicateSubmitsCreateSinglePlan() throws Exception {
        PlanSubmitRequest req = request("p12", "r12", node("a", "PASS", List.of(), null));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PlanView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.submit(req);
            }));
        }
        try {
            start.countDown();
            long replays = 0;
            for (Future<PlanView> future : futures) {
                if (future.get(10, TimeUnit.SECONDS).idempotentReplay()) {
                    replays++;
                }
            }
            assertEquals(threads - 1, replays);
        } finally {
            pool.shutdownNow();
        }
        Integer plans = jdbc.queryForObject("SELECT COUNT(*) FROM launch_plan", Integer.class);
        assertEquals(1, plans);
        assertEquals(1, service.getEvents("p12").stream()
                .filter(e -> e.eventType().equals("PLAN_SUBMITTED")).count());
    }
}
