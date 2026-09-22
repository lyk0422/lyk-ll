package com.example.starter.ros.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ros.AbstractIntegrationTest;
import com.example.starter.ros.TestDataCleaner;
import com.example.starter.ros.domain.EventType;
import com.example.starter.ros.domain.ResourceCategory;
import com.example.starter.ros.repository.EventJdbcRepository;
import com.example.starter.ros.repository.NodeJdbcRepository;
import com.example.starter.ros.repository.ResourceJdbcRepository;
import com.example.starter.ros.web.dto.NodeOperationRequest;
import com.example.starter.ros.web.dto.NodeResponse;
import com.example.starter.ros.web.dto.RegisterNodeRequest;
import com.example.starter.ros.web.dto.ResourceSpec;
import com.example.starter.ros.web.dto.TransitionRequest;
import com.example.starter.ros.web.error.ApiException;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发注册、并发版本迁移、同 requestId 并发重放与崩溃/注销竞争测试,
 * 使用真实线程与 H2 数据库约束验证,不使用 mock。
 */
class NodeRegistryConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private NodeRegistryService service;
    @Autowired
    private EventJdbcRepository eventRepository;
    @Autowired
    private NodeJdbcRepository nodeRepository;
    @Autowired
    private ResourceJdbcRepository resourceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService pool;

    @BeforeEach
    void clean() {
        TestDataCleaner.cleanAll(jdbcTemplate);
        pool = Executors.newFixedThreadPool(2);
    }

    private RegisterNodeRequest nodeWithService(String nodeId, String requestId) {
        return new RegisterNodeRequest(nodeId, "/robot", "node_" + nodeId,
                List.of(new ResourceSpec("SERVICE", "shared_svc",
                        "example_interfaces/srv/AddTwoInts")), requestId);
    }

    @Test
    void concurrentRegistrationsOfSameExclusiveNameYieldExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        var futures = List.of("n1", "n2").stream().map(nodeId -> pool.submit(() -> {
            start.await();
            try {
                service.register(nodeWithService(nodeId, "req-" + nodeId));
                success.incrementAndGet();
            } catch (ApiException e) {
                assertThat(e.getCode()).isEqualTo("RESOURCE_CONFLICT");
                conflicts.incrementAndGet();
            }
            return null;
        })).toList();

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(resourceRepository.findClaimHolder(
                ResourceCategory.SERVICE, "/robot/shared_svc")).isPresent();
        long claimCount = (long) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_claim WHERE category = 'SERVICE'"
                        + " AND fq_name = '/robot/shared_svc'", Long.class);
        assertThat(claimCount).isEqualTo(1L);
        // 失败方事务整体回滚:不残留节点行
        assertThat(nodeRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentTransitionsWithSameExpectedVersionYieldExactlyOneSuccess() throws Exception {
        service.register(new RegisterNodeRequest("n1", "/robot", "talker",
                List.of(), "req-register"));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger versionConflicts = new AtomicInteger();

        var futures = List.of("req-a", "req-b").stream().map(requestId ->
                pool.submit(() -> {
                    start.await();
                    try {
                        service.transition(new TransitionRequest(
                                "n1", "INACTIVE", 0L, requestId));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT");
                        versionConflicts.incrementAndGet();
                    }
                    return null;
                })).toList();

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(versionConflicts.get()).isEqualTo(1);
        assertThat(nodeRepository.findById("n1").orElseThrow().version()).isEqualTo(1L);
        long stateChangedEvents = eventRepository.findAfter(0, 50).stream()
                .filter(e -> e.eventType() == EventType.STATE_CHANGED).count();
        assertThat(stateChangedEvents).isEqualTo(1L);
    }

    @Test
    void concurrentSameRequestIdReplayProducesSingleNodeAndSingleEvent() throws Exception {
        RegisterNodeRequest request = new RegisterNodeRequest("n1", "/robot", "talker",
                List.of(new ResourceSpec("TOPIC", "chatter", "std_msgs/msg/String")),
                "shared-request-id");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<NodeResponse>> futures = List.of(1, 2).stream().map(i -> pool.submit(() -> {
            start.await();
            return service.register(request);
        })).toList();

        start.countDown();
        NodeResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        NodeResponse second = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(first).usingRecursiveComparison().isEqualTo(second);
        assertThat(nodeRepository.findAll()).hasSize(1);
        assertThat(resourceRepository.findByNodeId("n1")).hasSize(1);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(1);
    }

    @Test
    void concurrentCrashAndDeregisterProduceSingleCleanup() throws Exception {
        service.register(new RegisterNodeRequest("n1", "/robot", "talker",
                List.of(new ResourceSpec("SERVICE", "svc",
                        "example_interfaces/srv/AddTwoInts")), "req-register"));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        var futures = List.<Runnable>of(
                () -> {
                    try {
                        service.crash(new NodeOperationRequest("n1", "req-crash"));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        failures.incrementAndGet();
                    }
                },
                () -> {
                    try {
                        service.deregister(new NodeOperationRequest("n1", "req-deregister"));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        failures.incrementAndGet();
                    }
                }
        ).stream().map(task -> pool.submit(() -> {
            start.await();
            task.run();
            return null;
        })).toList();

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(resourceRepository.findByNodeId("n1")).isEmpty();
        assertThat(resourceRepository.findClaimHolder(
                ResourceCategory.SERVICE, "/robot/svc")).isEmpty();
        long terminalEvents = eventRepository.findAfter(0, 50).stream()
                .filter(e -> e.eventType() == EventType.CRASHED
                        || e.eventType() == EventType.DEREGISTERED).count();
        assertThat(terminalEvents).isEqualTo(1L);
    }
}
