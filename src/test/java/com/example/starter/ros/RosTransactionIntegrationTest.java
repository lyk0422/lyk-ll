package com.example.starter.ros;

import com.example.starter.ros.adapter.SimulatedNodeAdapter;
import com.example.starter.ros.db.EventRepository;
import com.example.starter.ros.db.NodeStateRepository;
import com.example.starter.ros.support.DemoSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2(MODE=MySQL) 事务集成测试：默认值、Schema 失败、依赖冲突、节点拒绝、超时全量回滚、
 * 静态参数保护、重复事务幂等/冲突、事件续传与并发幂等边界。
 */
@SpringBootTest
class RosTransactionIntegrationTest {

    private static final String CAMERA = DemoSchemaInitializer.CAMERA_NODE;
    private static final String PLANNER = DemoSchemaInitializer.PLANNER_NODE;

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ParameterQueryService parameterQueryService;
    @Autowired
    private PendingRebootService pendingRebootService;
    @Autowired
    private EventSubscriptionService eventSubscriptionService;
    @Autowired
    private SimulatedNodeAdapter nodeAdapter;
    @Autowired
    private NodeStateRepository nodeStateRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        nodeAdapter.resetAll();
        // 保留 param_schema（示例 Schema 建表后幂等注册）；清理全部业务数据避免顺序依赖
        jdbcTemplate.update("DELETE FROM committed_param");
        jdbcTemplate.update("DELETE FROM node_state");
        jdbcTemplate.update("DELETE FROM transaction_record");
        jdbcTemplate.update("DELETE FROM param_event");
        jdbcTemplate.update("DELETE FROM subscription_ack");
        jdbcTemplate.update("DELETE FROM pending_reboot");
    }

    @Test
    void readsMergedDefaultsBeforeAnyCommit() {
        Map<String, Object> effective = parameterQueryService.effectiveValues(CAMERA);
        assertEquals(30L, effective.get("frame_rate"));
        assertEquals(640L, effective.get("resolution_width"));
        assertEquals("SIM-CAM-0001", effective.get("device_serial"));
    }

    @Test
    void twoNodeSuccessCommitsVersionsValuesAndSingleEvent() {
        TransactionRequest request = twoNodeRequest("tx-success", 0, 0,
                Map.of("frame_rate", 60, "resolution_width", 1280, "resolution_height", 720),
                Map.of("max_speed", 1.8));

        TransactionResponse response = transactionService.execute(request);

        assertEquals(TransactionStatus.COMMITTED, response.status());
        assertFalse(response.replayed());
        assertEquals(OutcomeStatus.ACCEPTED, response.nodes().get(CAMERA).status());
        assertEquals(OutcomeStatus.ACCEPTED, response.nodes().get(PLANNER).status());
        assertEquals(1L, response.nodes().get(CAMERA).currentVersion());
        assertEquals(1L, response.nodes().get(PLANNER).currentVersion());
        assertNotNull(response.eventCursor());
        // 整笔事务恰好一条事件
        assertEquals(1, eventRepository.findAfter(0, 100).size());
        // 默认值与已提交值合并
        assertEquals(60L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));
        assertEquals(720L, parameterQueryService.effectiveValues(CAMERA).get("resolution_height"));
        assertEquals("SIM-CAM-0001", parameterQueryService.effectiveValues(CAMERA).get("device_serial"));
        assertEquals(1.8, parameterQueryService.effectiveValues(PLANNER).get("max_speed"));
        // 节点侧已应用值同步
        assertEquals(60L, nodeAdapter.appliedValues(CAMERA).get("frame_rate"));
        assertEquals(1.8, nodeAdapter.appliedValues(PLANNER).get("max_speed"));
    }

    @Test
    void dependencyConflictRollsEverythingBack() {
        // height > width 违反跨参数依赖
        TransactionRequest request = singleNodeRequest("tx-dep", CAMERA, 0,
                Map.of("resolution_width", 320, "resolution_height", 480));

        TransactionResponse response = transactionService.execute(request);

        assertEquals(TransactionStatus.ROLLED_BACK, response.status());
        assertEquals(OutcomeStatus.REJECTED, response.nodes().get(CAMERA).status());
        assertTrue(response.nodes().get(CAMERA).reason().contains("resolution_height"));
        assertNull(response.eventCursor());
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
        // 已提交值未被污染，仍是默认值
        assertEquals(640L, parameterQueryService.effectiveValues(CAMERA).get("resolution_width"));
        assertEquals(480L, parameterQueryService.effectiveValues(CAMERA).get("resolution_height"));
        assertTrue(nodeAdapter.appliedValues(CAMERA).isEmpty());
    }

    @Test
    void schemaFailuresAreRejectedWith400() {
        TransactionRequest unknownParam = singleNodeRequest("tx-unknown", CAMERA, 0,
                Map.of("not_a_param", 1));
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> transactionService.execute(unknownParam));
        assertTrue(ex.getMessage().contains("not_a_param"));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());

        TransactionRequest wrongType = singleNodeRequest("tx-type", CAMERA, 0,
                Map.of("frame_rate", "not-a-number"));
        assertThrows(SchemaValidationException.class, () -> transactionService.execute(wrongType));
    }

    @Test
    void nodeRejectionCausesFullCrossNodeRollback() {
        nodeAdapter.reject(PLANNER, "speed profile incompatible with current map");
        TransactionRequest request = twoNodeRequest("tx-reject", 0, 0,
                Map.of("frame_rate", 60), Map.of("max_speed", 1.0));

        TransactionResponse response = transactionService.execute(request);

        assertEquals(TransactionStatus.ROLLED_BACK, response.status());
        assertEquals(OutcomeStatus.ACCEPTED, response.nodes().get(CAMERA).status());
        assertEquals(OutcomeStatus.REJECTED, response.nodes().get(PLANNER).status());
        assertTrue(response.nodes().get(PLANNER).reason().contains("speed profile"));
        // 即使 camera 节点本地校验通过，数据库值与节点应用值均保持原状
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(0L, nodeStateRepository.currentVersion(PLANNER));
        assertEquals(30L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));
        assertTrue(nodeAdapter.appliedValues(CAMERA).isEmpty());
        assertTrue(nodeAdapter.appliedValues(PLANNER).isEmpty());
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
    }

    @Test
    void nodeTimeoutCausesFullRollback() {
        // 延迟 2s，远超测试时限 500ms
        nodeAdapter.delay(CAMERA, 2000L);
        TransactionRequest request = twoNodeRequest("tx-timeout", 0, 0,
                Map.of("frame_rate", 60), Map.of("max_speed", 1.0));

        long start = System.nanoTime();
        TransactionResponse response = transactionService.execute(request);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(TransactionStatus.ROLLED_BACK, response.status());
        assertEquals(OutcomeStatus.TIMEOUT, response.nodes().get(CAMERA).status());
        assertEquals(OutcomeStatus.ACCEPTED, response.nodes().get(PLANNER).status());
        // 有界等待：总耗时远小于节点延迟 2s
        assertTrue(elapsedMillis < 1500L, "timeout should be bounded, elapsed=" + elapsedMillis);
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(0L, nodeStateRepository.currentVersion(PLANNER));
        assertEquals(30L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));
        assertTrue(nodeAdapter.appliedValues(CAMERA).isEmpty());
        assertTrue(nodeAdapter.appliedValues(PLANNER).isEmpty());
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
    }

    @Test
    void versionConflictReportedPerNodeAndRolledBack() {
        TransactionRequest request = twoNodeRequest("tx-ver", 0, 7L,
                Map.of("frame_rate", 60), Map.of("max_speed", 1.0));

        TransactionResponse response = transactionService.execute(request);

        assertEquals(TransactionStatus.ROLLED_BACK, response.status());
        assertEquals(OutcomeStatus.VERSION_CONFLICT, response.nodes().get(PLANNER).status());
        assertEquals(0L, nodeStateRepository.currentVersion(PLANNER));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
    }

    @Test
    void staticParameterHotUpdateReturns409WithParamName() {
        TransactionRequest request = singleNodeRequest("tx-static", CAMERA, 0,
                Map.of("device_serial", "OTHER-SERIAL"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> transactionService.execute(request));
        assertTrue(ex.getMessage().contains("device_serial"), ex.getMessage());
        // 已生效值未改变、版本与事件不变
        assertEquals("SIM-CAM-0001", parameterQueryService.effectiveValues(CAMERA).get("device_serial"));
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());

        // 修改静态参数只能走待重启配置，且不伪装为已应用
        PendingRebootRequest.Response pending = pendingRebootService.register(CAMERA,
                new PendingRebootRequest("pending-static", Map.of("device_serial", "OTHER-SERIAL")));
        assertFalse(pending.applied());
        assertEquals("OTHER-SERIAL", pending.pending().get("device_serial"));
        assertEquals("SIM-CAM-0001", parameterQueryService.effectiveValues(CAMERA).get("device_serial"));
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
    }

    @Test
    void duplicateTransactionSameParamsReturnsOriginalResult() {
        TransactionRequest first = singleNodeRequest("tx-dup", CAMERA, 0, Map.of("frame_rate", 90));
        TransactionResponse response1 = transactionService.execute(first);
        assertEquals(TransactionStatus.COMMITTED, response1.status());

        TransactionResponse response2 = transactionService.execute(singleNodeRequest(
                "tx-dup", CAMERA, 0, Map.of("frame_rate", 90)));
        assertTrue(response2.replayed());
        assertEquals(response1.eventCursor(), response2.eventCursor());
        assertEquals(1L, response2.nodes().get(CAMERA).currentVersion());
        // 重放不重复推进版本、不重复产生事件、不重复触发节点应用
        assertEquals(1L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(1, eventRepository.findAfter(0, 100).size());
        // 重放不重复触发节点应用：已应用值仍为首次提交的候选快照（5 个参数）
        assertEquals(5, nodeAdapter.appliedValues(CAMERA).size());
        assertEquals(90L, nodeAdapter.appliedValues(CAMERA).get("frame_rate"));
    }

    @Test
    void duplicateTransactionDifferentParamsReturns409() {
        transactionService.execute(singleNodeRequest("tx-clash", CAMERA, 0, Map.of("frame_rate", 60)));
        TransactionRequest changed = singleNodeRequest("tx-clash", CAMERA, 1, Map.of("frame_rate", 90));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> transactionService.execute(changed));
        assertTrue(ex.getMessage().contains("tx-clash"));
        assertEquals(1L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(60L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));
    }

    @Test
    void rolledBackTransactionIsAlsoIdempotent() {
        nodeAdapter.reject(PLANNER, "nope");
        TransactionRequest request = twoNodeRequest("tx-rollback-idem", 0, 0,
                Map.of("frame_rate", 60), Map.of("max_speed", 1.0));
        TransactionResponse first = transactionService.execute(request);
        assertEquals(TransactionStatus.ROLLED_BACK, first.status());

        // 节点即使后续恢复接受，同键同参仍返回原回滚结果，不会二次提交
        nodeAdapter.accept(PLANNER);
        TransactionResponse replay = transactionService.execute(request);
        assertTrue(replay.replayed());
        assertEquals(TransactionStatus.ROLLED_BACK, replay.status());
        assertEquals(0L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(0L, nodeStateRepository.currentVersion(PLANNER));
        assertTrue(eventRepository.findAfter(0, 100).isEmpty());
    }

    @Test
    void eventsResumeOnlyAfterAckedCursorWithoutDuplicates() {
        String subscriber = "watchdog";
        transactionService.execute(singleNodeRequest("tx-ev1", CAMERA, 0, Map.of("frame_rate", 40)));

        List<EventRecord> firstPull = eventSubscriptionService.pull(subscriber, 10);
        assertEquals(1, firstPull.size());
        long cursor = firstPull.get(0).id();
        assertEquals("tx-ev1", firstPull.get(0).transactionId());

        // 断线重连（未 ACK）：同一事件仍可见，保证至少一次
        assertEquals(1, eventSubscriptionService.pull(subscriber, 10).size());

        eventSubscriptionService.ack(subscriber, cursor);
        // ACK 后重连：不重复投递已确认事件
        assertTrue(eventSubscriptionService.pull(subscriber, 10).isEmpty());

        transactionService.execute(singleNodeRequest("tx-ev2", CAMERA, 1, Map.of("frame_rate", 50)));
        List<EventRecord> secondPull = eventSubscriptionService.pull(subscriber, 10);
        assertEquals(1, secondPull.size());
        assertEquals("tx-ev2", secondPull.get(0).transactionId());
        assertTrue(secondPull.get(0).id() > cursor);
    }

    @Test
    void concurrentSameTransactionCommitsExactlyOnce() throws Exception {
        TransactionRequest request = singleNodeRequest("tx-concurrent", CAMERA, 0,
                Map.of("frame_rate", 60));
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransactionResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return transactionService.execute(request);
            }));
        }
        start.countDown();
        int committed = 0;
        int replayed = 0;
        for (Future<TransactionResponse> future : futures) {
            TransactionResponse response = future.get(10, TimeUnit.SECONDS);
            if (response.replayed()) {
                replayed++;
            } else if (response.status() == TransactionStatus.COMMITTED) {
                committed++;
            }
        }
        pool.shutdown();

        assertEquals(threads, committed + replayed);
        assertEquals(1, committed);
        assertEquals(threads - 1, replayed);
        assertEquals(1L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(1, eventRepository.findAfter(0, 100).size());
    }

    @Test
    void resetToNullRestoresDefaultAndBumpsVersion() {
        transactionService.execute(singleNodeRequest("tx-set", CAMERA, 0, Map.of("frame_rate", 90)));
        assertEquals(90L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("frame_rate", null);
        TransactionResponse response = transactionService.execute(
                singleNodeRequest("tx-reset", CAMERA, 1, values));
        assertEquals(TransactionStatus.COMMITTED, response.status());
        assertEquals(2L, nodeStateRepository.currentVersion(CAMERA));
        assertEquals(30L, parameterQueryService.effectiveValues(CAMERA).get("frame_rate"));
        assertTrue(nodeStateRepository.committedValues(CAMERA).isEmpty());
    }

    private static TransactionRequest singleNodeRequest(String txId, String nodeId,
                                                        long expectedVersion,
                                                        Map<String, Object> values) {
        return new TransactionRequest(txId,
                Map.of(nodeId, new NodeChange(expectedVersion, new LinkedHashMap<>(values))));
    }

    private static TransactionRequest twoNodeRequest(String txId, long cameraVersion,
                                                     long plannerVersion,
                                                     Map<String, Object> cameraValues,
                                                     Map<String, Object> plannerValues) {
        Map<String, NodeChange> nodes = new LinkedHashMap<>();
        nodes.put(CAMERA, new NodeChange(cameraVersion, new LinkedHashMap<>(cameraValues)));
        nodes.put(PLANNER, new NodeChange(plannerVersion, new LinkedHashMap<>(plannerValues)));
        return new TransactionRequest(txId, nodes);
    }
}
