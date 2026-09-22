package com.example.starter.ros;

import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.ResourceSpec;
import com.example.starter.ros.api.TransitionRequest;
import com.example.starter.ros.domain.RegistryErrorCode;
import com.example.starter.ros.domain.RegistryException;
import com.example.starter.ros.service.NodeRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发注册与并发同 requestId 请求的 H2 集成测试，使用真实线程与超时协调。
 */
class ConcurrencyTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private NodeRegistryService service;

    @Test
    void concurrent_registrations_of_same_exclusive_name_have_exactly_one_winner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                final int index = i;
                Callable<Outcome> task = () -> {
                    start.await();
                    try {
                        NodeView view = service.register(new RegisterNodeRequest(
                                "node-" + index, "/robot", "race_node",
                                List.of(new ResourceSpec("SERVICE", "/race/svc",
                                        "example/srv/Trigger")),
                                "req-race-" + index));
                        return Outcome.success(view);
                    } catch (RegistryException ex) {
                        return Outcome.failure(ex.getErrorCode());
                    }
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(30, TimeUnit.SECONDS);
                if (outcome.success) {
                    success++;
                } else if (outcome.errorCode == RegistryErrorCode.RESOURCE_CONFLICT) {
                    conflict++;
                }
            }
            assertEquals(1, success, "仅一个存活节点可持有同名 Service");
            assertEquals(threads - 1, conflict);

            // 最终只有一个节点、一份资源、一条声明事件
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM ros_node", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM node_resource", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM resource_event", Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrent_same_requestId_registration_executes_once_and_both_threads_replay_result()
            throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            RegisterNodeRequest request = new RegisterNodeRequest("node-1", "/robot", "idem_node",
                    List.of(new ResourceSpec("TOPIC", "/idem/topic", "std_msgs/msg/Empty")),
                    "req-shared");
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return Outcome.success(service.register(request));
                    } catch (RegistryException ex) {
                        return Outcome.failure(ex.getErrorCode());
                    }
                }));
            }
            start.countDown();

            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(30, TimeUnit.SECONDS);
                assertTrue(outcome.success, "同参并发请求都应成功（首执行 + 重放）");
                assertNotNull(outcome.view);
                assertEquals("node-1", outcome.view.nodeId());
            }
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM ros_node", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM idempotent_request", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM resource_event", Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrent_same_requestId_transition_executes_once() throws Exception {
        service.register(new RegisterNodeRequest("node-1", "/robot", "tx_node",
                List.of(new ResourceSpec("TOPIC", "/tx/topic", "std_msgs/msg/Empty")),
                "req-reg"));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return Outcome.success(service.transition("node-1",
                                new TransitionRequest("INACTIVE", 1L, "req-cfg-shared")));
                    } catch (RegistryException ex) {
                        return Outcome.failure(ex.getErrorCode());
                    }
                }));
            }
            start.countDown();

            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(30, TimeUnit.SECONDS);
                assertTrue(outcome.success);
                assertEquals("INACTIVE", outcome.view.lifecycleState());
                assertEquals(2L, outcome.view.version());
            }
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM resource_event WHERE event_type = 'LIFECYCLE_TRANSITION'",
                    Integer.class), "并发同 requestId 状态请求只执行一次迁移");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 线程执行结果。 */
    private static final class Outcome {
        private final boolean success;
        private final NodeView view;
        private final RegistryErrorCode errorCode;

        private Outcome(boolean success, NodeView view, RegistryErrorCode errorCode) {
            this.success = success;
            this.view = view;
            this.errorCode = errorCode;
        }

        static Outcome success(NodeView view) {
            return new Outcome(true, view, null);
        }

        static Outcome failure(RegistryErrorCode code) {
            return new Outcome(false, null, code);
        }
    }
}
