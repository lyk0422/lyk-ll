package com.example.starter;

import com.example.starter.model.MaintenanceAcceptanceRole;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检修封锁并发门禁测试（真实 H2 事务与行锁，非 mock）：
 * 检修生效/验收与上客并发必须形成一致次序，不允许有效封锁与新上客同时成功。
 */
class MaintenanceConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void boardingVsEffectiveOrderCreationNeverOverlap() throws Exception {
        Instant now = clock.now();
        // 窗口起点已在过去：检修单一经提交即生效封锁
        MaintenanceOrderRequest orderRequest = new MaintenanceOrderRequest(
                UUID.randomUUID().toString(), now.minusSeconds(60), now.plusSeconds(3600),
                "紧急检修", List.of(SEGMENT_1, SEGMENT_2, SEGMENT_3));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            outcomes.add(pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 2));
                    return "OK";
                } catch (ApiException e) {
                    assertThat(e.getMessage()).contains("检修封锁");
                    return "BLOCKED";
                }
            }));
        }
        Future<Long> orderFuture = pool.submit(() -> {
            gate.await();
            JsonNode resp = readJson(maintenanceService.createOrder(LINE_ID, orderRequest).getBody());
            return resp.get("id").asLong();
        });
        gate.countDown();

        long orderId = orderFuture.get(30, TimeUnit.SECONDS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        for (Future<String> outcome : outcomes) {
            if ("OK".equals(outcome.get(30, TimeUnit.SECONDS))) {
                ok.incrementAndGet();
            } else {
                blocked.incrementAndGet();
            }
        }
        pool.shutdown();

        // 关键不变量：检修单提交之后不允许任何新上客落库（提交前的上客才允许）
        Integer boardingAfterBlock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boarding WHERE line_id = ? AND created_at > "
                        + "(SELECT created_at FROM maintenance_order WHERE id = ?)",
                Integer.class, LINE_ID, orderId);
        assertThat(boardingAfterBlock).isZero();
        assertThat(ok.get() + blocked.get()).isEqualTo(threads);
        assertThat(countRows("boarding")).isEqualTo(ok.get());
        assertThat(blocked.get() + ok.get()).isEqualTo(threads);
    }

    @Test
    void boardingVsAcceptanceHasConsistentOrder() throws Exception {
        Instant now = clock.now();
        long orderId = readJson(maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(UUID.randomUUID().toString(),
                        now.minusSeconds(3600), now, "年检", List.of(SEGMENT_1, SEGMENT_2))).getBody())
                .get("id").asLong();
        registerClearance(orderId, SEGMENT_1, "evac-1");
        registerClearance(orderId, SEGMENT_2, "evac-2");
        // 并发开始前：有效封锁，上客被拒
        assertBlocked();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            outcomes.add(pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 2));
                    return "OK";
                } catch (ApiException e) {
                    assertThat(e.getMessage()).contains("检修封锁");
                    return "BLOCKED";
                }
            }));
        }
        Future<?> finisher = pool.submit(() -> {
            gate.await();
            accept(orderId, 1, MaintenanceAcceptanceRole.MECHANICAL, "mech-1");
            accept(orderId, 1, MaintenanceAcceptanceRole.REPAIR, "repair-1");
            return null;
        });
        gate.countDown();
        finisher.get(30, TimeUnit.SECONDS);

        AtomicInteger ok = new AtomicInteger();
        for (Future<String> outcome : outcomes) {
            if ("OK".equals(outcome.get(30, TimeUnit.SECONDS))) {
                ok.incrementAndGet();
            }
        }
        pool.shutdown();

        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        // 关键不变量：不存在“封锁仍有效时”落库的上客记录，即上客记录不得早于验收完成时刻
        Integer boardingBeforeCompletion = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boarding WHERE line_id = ? AND created_at < "
                        + "(SELECT completed_at FROM maintenance_order WHERE id = ?)",
                Integer.class, LINE_ID, orderId);
        assertThat(boardingBeforeCompletion).isZero();
        // 验收完成后，后续上客全部成功
        for (int i = 0; i < 4; i++) {
            assertThat(controlEventService.board(LINE_ID,
                    new BoardingRequest(UUID.randomUUID().toString(), 1)).getStatusCode().value())
                    .isEqualTo(200);
        }
    }

    @Test
    void maintenanceOperationsSerializeOnLineRowLock() throws Exception {
        Instant now = clock.now();
        long orderId = readJson(maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderRequest(UUID.randomUUID().toString(),
                        now.minusSeconds(3600), now.plusSeconds(3600), "封锁", List.of(SEGMENT_1))).getBody())
                .get("id").asLong();

        // 在独立连接上持有线路行锁，检修清空登记与上客都必须排队等待
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                ResultSet rs = st.executeQuery(
                        "SELECT id FROM wind_line WHERE id = " + LINE_ID + " FOR UPDATE");
                assertThat(rs.next()).isTrue();
            }

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            Future<?> clearance = pool.submit(() -> {
                gate.await();
                maintenanceService.registerClearance(orderId,
                        new MaintenanceClearanceRequest(UUID.randomUUID().toString(),
                                SEGMENT_1, "evac-1", "逐段清空"));
                return null;
            });
            Future<?> boarding = pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 1));
                } catch (ApiException expected) {
                    // 拿到锁时检修仍生效：应被检修封锁拒绝
                    assertThat(expected.getMessage()).contains("检修封锁");
                }
                return null;
            });
            gate.countDown();

            // 持锁期间：两个写操作均不能落库
            Thread.sleep(Duration.ofMillis(500).toMillis());
            assertThat(countRows("maintenance_clearance")).isZero();
            Integer boardingWhileLocked = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM boarding", Integer.class);
            // 上客请求被行锁阻塞，尚无成功落库（若提前抛锁超时也不允许产生记录）
            assertThat(boardingWhileLocked).isZero();

            holder.commit();
            clearance.get(15, TimeUnit.SECONDS);
            boarding.get(15, TimeUnit.SECONDS);
            pool.shutdown();
        }
        assertThat(countRows("maintenance_clearance")).isEqualTo(1);
    }

    private void assertBlocked() {
        try {
            controlEventService.board(LINE_ID, new BoardingRequest(UUID.randomUUID().toString(), 1));
            throw new AssertionError("生效检修封锁期间不应允许上客");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("检修封锁");
        }
    }

    private void registerClearance(long orderId, long segmentId, String operatorId) {
        maintenanceService.registerClearance(orderId,
                new MaintenanceClearanceRequest(UUID.randomUUID().toString(), segmentId,
                        operatorId, "按原能力逐段疏散清空"));
    }

    private JsonNode accept(long orderId, int expectedVersion, MaintenanceAcceptanceRole role,
                            String operatorId) {
        return readJson(maintenanceService.accept(orderId,
                new MaintenanceAcceptanceRequest(UUID.randomUUID().toString(), expectedVersion,
                        role, operatorId, "验收合格")).getBody());
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM maintenance_order WHERE id = ?",
                String.class, orderId);
    }
}
