package com.example.starter.rosconfig;

import com.example.starter.rosconfig.adapter.NodeBehavior;
import com.example.starter.rosconfig.adapter.SimulatedNodeAdapter;
import com.example.starter.rosconfig.dto.ChangeSetRequest;
import com.example.starter.rosconfig.dto.NodeResultStatus;
import com.example.starter.rosconfig.dto.TransactionRequest;
import com.example.starter.rosconfig.dto.TransactionResponse;
import com.example.starter.rosconfig.service.ConfigQueryService;
import com.example.starter.rosconfig.service.EventService;
import com.example.starter.rosconfig.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2（MODE=MySQL）事务式热更新集成测试：真实数据库约束、事务回滚、并发写入，
 * 不使用 mock 或内存 Map 代替数据库边界。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ConfigQueryService configQueryService;
    @Autowired
    private EventService eventService;
    @Autowired
    private SimulatedNodeAdapter nodeAdapter;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM param_value");
        jdbc.update("DELETE FROM node_config_version");
        jdbc.update("DELETE FROM config_event");
        jdbc.update("DELETE FROM transaction_record");
        jdbc.update("DELETE FROM subscriber_cursor");
        nodeAdapter.reset();
    }

    private TransactionRequest twoNodeSuccess(String txnId) {
        return new TransactionRequest(txnId, List.of(
                new ChangeSetRequest("/camera", 0L, Map.of("frame_rate", 45)),
                new ChangeSetRequest("/planner", 0L, Map.of("max_speed", 2.0))));
    }

    @Test
    void viewMergesDefaultsWithCommittedValues() {
        assertThat(configQueryService.view("/camera").params())
                .containsEntry("frame_rate", 30L)
                .containsEntry("resolution", "1080p")
                .containsEntry("camera_index", 0L);
        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/camera").dynamic())
                .containsEntry("frame_rate", true)
                .containsEntry("camera_index", false);
    }

    @Test
    void successfulTwoNodeTransactionCommitsAtomically() {
        TransactionResponse response = transactionService.execute(twoNodeSuccess("txn-ok-1"));

        assertThat(response.committed()).isTrue();
        assertThat(response.eventCursor()).isNotNull().isPositive();
        assertThat(response.versions()).containsEntry("/camera", 1L).containsEntry("/planner", 1L);
        assertThat(response.results()).extracting(r -> r.status())
                .containsExactly(NodeResultStatus.ACCEPTED, NodeResultStatus.ACCEPTED);

        assertThat(configQueryService.view("/camera").version()).isEqualTo(1L);
        assertThat(configQueryService.view("/camera").params()).containsEntry("frame_rate", 45L);
        assertThat(configQueryService.view("/planner").version()).isEqualTo(1L);
        assertThat(configQueryService.view("/planner").params()).containsEntry("max_speed", 2.0);

        assertThat(nodeAdapter.appliedView("/camera")).containsEntry("frame_rate", 45L);
        assertThat(nodeAdapter.appliedView("/planner")).containsEntry("max_speed", 2.0);

        List<EventService.Event> events = eventService.poll("s1", 0L, 100);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).cursor()).isEqualTo(response.eventCursor());
        assertThat(events.get(0).transactionId()).isEqualTo("txn-ok-1");
    }

    @Test
    void schemaDependencyFailureRollsEverythingBack() {
        TransactionRequest request = new TransactionRequest("txn-dep-1", List.of(
                new ChangeSetRequest("/camera", 0L, Map.of("frame_rate", 90)),
                new ChangeSetRequest("/planner", 0L, Map.of("max_speed", 2.0))));

        TransactionResponse response = transactionService.execute(request);

        assertThat(response.committed()).isFalse();
        assertThat(response.eventCursor()).isNull();
        assertThat(response.results())
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/camera");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.SCHEMA_REJECTED);
                    assertThat(r.message()).contains("frame_rate");
                })
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/planner");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.SKIPPED);
                });

        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/camera").params()).containsEntry("frame_rate", 30L);
        assertThat(configQueryService.view("/planner").version()).isZero();
        assertThat(nodeAdapter.appliedView("/camera")).isEmpty();
        assertThat(eventService.poll("s1", 0L, 100)).isEmpty();
    }

    @Test
    void missingRequiredParameterIsRejected() {
        // 显式置空必填参数：Map.of 不允许 null，使用可空 Map 构造
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("frame_rate", null);
        params.put("resolution", "4k");
        TransactionRequest request = new TransactionRequest("txn-req-1", List.of(
                new ChangeSetRequest("/camera", 0L, params)));

        TransactionResponse response = transactionService.execute(request);

        assertThat(response.committed()).isFalse();
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(NodeResultStatus.SCHEMA_REJECTED);
        assertThat(response.results().get(0).message()).contains("missing required parameter: frame_rate");
        assertThat(configQueryService.view("/camera").version()).isZero();
    }

    @Test
    void nodeRejectionRollsBackAllNodesAndEventStream() {
        nodeAdapter.setBehavior("/planner", NodeBehavior.REJECT);

        TransactionResponse response = transactionService.execute(twoNodeSuccess("txn-reject-1"));

        assertThat(response.committed()).isFalse();
        assertThat(response.results())
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/planner");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.REJECTED);
                    assertThat(r.message()).contains("rejected");
                })
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/camera");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.ACCEPTED);
                });

        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/planner").version()).isZero();
        assertThat(configQueryService.view("/camera").params()).containsEntry("frame_rate", 30L);
        assertThat(nodeAdapter.appliedView("/camera")).isEmpty();
        assertThat(nodeAdapter.appliedView("/planner")).isEmpty();
        assertThat(eventService.poll("s1", 0L, 100)).isEmpty();
    }

    @Test
    void nodeTimeoutRollsEverythingBack() {
        nodeAdapter.setBehavior("/camera", NodeBehavior.TIMEOUT);

        long started = System.currentTimeMillis();
        TransactionResponse response = transactionService.execute(twoNodeSuccess("txn-timeout-1"));
        long elapsed = System.currentTimeMillis() - started;

        assertThat(response.committed()).isFalse();
        assertThat(response.results())
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/camera");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.TIMEOUT);
                    assertThat(r.message()).contains("within");
                })
                .anySatisfy(r -> assertThat(r.node()).isEqualTo("/planner"));
        // 配置时限为 300ms，必须在秒级内判定超时并回滚，而不是等待节点 60s 挂起结束
        assertThat(elelapsedWithinTimeout(elapsed)).isTrue();

        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/planner").version()).isZero();
        assertThat(nodeAdapter.appliedView("/camera")).isEmpty();
        assertThat(eventService.poll("s1", 0L, 100)).isEmpty();
    }

    private static boolean elelapsedWithinTimeout(long elapsedMs) {
        return elapsedMs < 5_000L;
    }

    @Test
    void staticParameterHotUpdateReturns409NamingParameter() {
        TransactionRequest request = new TransactionRequest("txn-static-1", List.of(
                new ChangeSetRequest("/camera", 0L, Map.of("camera_index", 2))));

        assertThatThrownBy(() -> transactionService.execute(request))
                .hasMessageContaining("static parameters cannot be hot-updated: camera_index");

        // 静态参数热更新失败不得改动任何状态
        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/camera").params()).containsEntry("camera_index", 0L);
        assertThat(eventService.poll("s1", 0L, 100)).isEmpty();
    }

    @Test
    void staticParameterCanOnlyBeStagedForRestartAndIsNotApplied() {
        var view = configQueryService.stagePending("/camera", "camera_index", 3);
        assertThat(view.pendingRestart()).contains("camera_index");
        // 待重启配置不伪装为已应用：已提交视图仍是旧值，版本不前进
        assertThat(view.version()).isZero();
        assertThat(view.params()).containsEntry("camera_index", 0L);
        assertThat(nodeAdapter.appliedView("/camera")).isEmpty();
    }

    @Test
    void versionConflictFailsWithoutTouchingOtherNode() {
        TransactionRequest request = new TransactionRequest("txn-ver-1", List.of(
                new ChangeSetRequest("/camera", 9L, Map.of("frame_rate", 45)),
                new ChangeSetRequest("/planner", 0L, Map.of("max_speed", 2.0))));

        TransactionResponse response = transactionService.execute(request);

        assertThat(response.committed()).isFalse();
        assertThat(response.results())
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/camera");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.VERSION_CONFLICT);
                    assertThat(r.oldVersion()).isZero();
                })
                .anySatisfy(r -> {
                    assertThat(r.node()).isEqualTo("/planner");
                    assertThat(r.status()).isEqualTo(NodeResultStatus.SKIPPED);
                });
        assertThat(configQueryService.view("/camera").version()).isZero();
        assertThat(configQueryService.view("/planner").version()).isZero();
    }

    @Test
    void duplicateTransactionSameParametersReturnsOriginalResult() {
        TransactionResponse first = transactionService.execute(twoNodeSuccess("txn-dup-1"));
        TransactionResponse second = transactionService.execute(twoNodeSuccess("txn-dup-1"));

        assertThat(second.committed()).isTrue();
        assertThat(second.eventCursor()).isEqualTo(first.eventCursor());
        assertThat(second.versions()).isEqualTo(first.versions());
        // 幂等重放不得重复推进版本或重复写事件
        assertThat(configQueryService.view("/camera").version()).isEqualTo(1L);
        assertThat(configQueryService.view("/planner").version()).isEqualTo(1L);
        assertThat(eventService.poll("s1", 0L, 100)).hasSize(1);

        // 同键异参返回 409
        TransactionRequest different = new TransactionRequest("txn-dup-1", List.of(
                new ChangeSetRequest("/camera", 0L, Map.of("frame_rate", 50))));
        assertThatThrownBy(() -> transactionService.execute(different))
                .isInstanceOf(com.example.starter.rosconfig.exception.ConflictException.class)
                .hasMessageContaining("already used with different parameters");
    }

    @Test
    void failedTransactionIsAlsoIdempotentAndReplayable() {
        nodeAdapter.setBehavior("/planner", NodeBehavior.REJECT);
        TransactionResponse first = transactionService.execute(twoNodeSuccess("txn-faildup-1"));
        TransactionResponse second = transactionService.execute(twoNodeSuccess("txn-faildup-1"));
        assertThat(first.committed()).isFalse();
        assertThat(second.committed()).isFalse();
        assertThat(second.results()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.results());
        assertThat(eventService.poll("s1", 0L, 100)).isEmpty();
    }

    @Test
    void staticParameterFailureIsIdempotentlyReplayedAs409() {
        TransactionRequest request = new TransactionRequest("txn-staticdup-1", List.of(
                new ChangeSetRequest("/camera", 0L, Map.of("camera_index", 1))));
        assertThatThrownBy(() -> transactionService.execute(request)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> transactionService.execute(request)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("camera_index");
    }

    @Test
    void eventReconnectAfterAckDoesNotRedeliver() {
        transactionService.execute(twoNodeSuccess("txn-evt-1"));

        List<EventService.Event> firstPoll = eventService.poll("reconnect-sub", null, 100);
        assertThat(firstPoll).hasSize(1);
        long cursor = firstPoll.get(0).cursor();

        eventService.ack("reconnect-sub", cursor);
        // 模拟同一 JVM 内断线重连：不带游标，只续传严格大于已确认游标的事件
        assertThat(eventService.poll("reconnect-sub", null, 100)).isEmpty();
        assertThat(eventService.ackedCursor("reconnect-sub")).isEqualTo(cursor);

        // 新事件到达后从 ACK 位置继续
        TransactionResponse next = transactionService.execute(new TransactionRequest("txn-evt-2", List.of(
                new ChangeSetRequest("/camera", 1L, Map.of("frame_rate", 50)))));
        List<EventService.Event> resumed = eventService.poll("reconnect-sub", null, 100);
        assertThat(resumed).hasSize(1);
        assertThat(resumed.get(0).cursor()).isEqualTo(next.eventCursor());
        assertThat(resumed.get(0).cursor()).isGreaterThan(cursor);
    }

    @Test
    void concurrentIdenticalTransactionsCommitExactlyOnce() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TransactionResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return transactionService.execute(twoNodeSuccess("txn-conc-same"));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<TransactionResponse> future : futures) {
                TransactionResponse response = future.get(10, TimeUnit.SECONDS);
                assertThat(response.committed()).isTrue();
                assertThat(response.versions()).containsEntry("/camera", 1L).containsEntry("/planner", 1L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(configQueryService.view("/camera").version()).isEqualTo(1L);
        assertThat(configQueryService.view("/planner").version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_event", Long.class)).isEqualTo(1L);
    }

    @Test
    void concurrentCompetingTransactionsOnlyOneWins() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TransactionResponse>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return transactionService.execute(new TransactionRequest("txn-conc-a", List.of(
                        new ChangeSetRequest("/camera", 0L, Map.of("frame_rate", 40)))));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return transactionService.execute(new TransactionRequest("txn-conc-b", List.of(
                        new ChangeSetRequest("/camera", 0L, Map.of("frame_rate", 50)))));
            }));
            start.countDown();

            long committed = 0;
            long conflicts = 0;
            for (Future<TransactionResponse> future : futures) {
                TransactionResponse response = future.get(10, TimeUnit.SECONDS);
                if (response.committed()) {
                    committed++;
                } else if (response.results().stream()
                        .anyMatch(r -> r.status() == NodeResultStatus.VERSION_CONFLICT)) {
                    conflicts++;
                }
            }
            assertThat(committed).isEqualTo(1L);
            assertThat(conflicts).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        assertThat(configQueryService.view("/camera").version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_event", Long.class)).isEqualTo(1L);
    }
}
