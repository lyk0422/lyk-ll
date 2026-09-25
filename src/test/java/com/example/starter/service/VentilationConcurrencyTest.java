package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.FixedClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.starter.TestDataFactory.alarm;
import static com.example.starter.TestDataFactory.fan;
import static com.example.starter.TestDataFactory.silo;
import static com.example.starter.TestDataFactory.ventilation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 真实并发与事务边界测试：协调真实线程并发下达，断言最终数据，不靠休眠充数。
 */
@Import(FixedClockConfig.class)
class VentilationConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 26, 12, 0, 0);

    @Autowired
    private GrainSiloService service;

    /**
     * 同一仓并发下达两笔通风令：行锁串行 + 状态校验，唯一受理。
     */
    @Test
    void concurrent_orders_for_same_silo_only_one_accepted() throws Exception {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.createFan(fan("F1"));
        service.reportAlarm(alarm("a-c1", "S1", T1.minusHours(1), 18.0, 23.0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String requestId : List.of("v-c1-a", "v-c1-b")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.orderVentilation(ventilation(requestId, "S1", "F1", 60.0, T1, T2));
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long accepted = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted, "同一仓并发通风令必须唯一受理");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_operation WHERE silo_id = "
                        + "(SELECT id FROM silo WHERE code = 'S1')", Long.class),
                "落败事务必须回滚，只留一个作业");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fan_active", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT ventilation_rounds FROM silo WHERE code = 'S1'", Integer.class),
                "轮次只能递增一次");
    }

    /**
     * 两个仓并发使用同一台风机、同一作业窗：fan_active 主键冲突保证风机唯一占用。
     */
    @Test
    void concurrent_orders_same_fan_only_one_holds_fan() throws Exception {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.createSilo(silo("S2", 20.0, 2.0, 70.0));
        service.createFan(fan("F1"));
        service.reportAlarm(alarm("a-c2-a", "S1", T1.minusHours(2), 18.0, 23.0));
        service.reportAlarm(alarm("a-c2-b", "S2", T1.minusHours(2), 18.0, 23.0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                service.orderVentilation(ventilation("v-c2-a", "S1", "F1", 60.0, T1, T2));
                return null;
            }));
            futures.add(pool.submit(() -> {
                start.await();
                service.orderVentilation(ventilation("v-c2-b", "S2", "F1", 60.0, T1, T2));
                return null;
            }));
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (Exception ex) {
                    conflict++;
                }
            }
            assertEquals(1, success, "同一台风机同一时刻只服务一个仓");
            assertEquals(1, conflict, "占用落败方必须失败");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fan_active", Long.class),
                "风机锁唯一");
        // 落败方的作业窗随事务回滚，不得残留
        long executingOps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_operation WHERE status = 'EXECUTING'", Long.class);
        assertEquals(1, executingOps, "落败事务回滚后只应有一个执行中作业");
    }

    /**
     * 风机锁定失败时作业开始必须一起回滚：不得作业开始而风机未锁。
     */
    @Test
    void failed_fan_lock_rolls_back_operation_atomically() {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.createFan(fan("F1"));
        service.reportAlarm(alarm("a-c3", "S1", T1.minusHours(1), 18.0, 23.0));

        // 预置风机占用（模拟锁定冲突）
        Long fanId = jdbc.queryForObject("SELECT id FROM fan WHERE code = 'F1'", Long.class);
        Long siloId = jdbc.queryForObject("SELECT id FROM silo WHERE code = 'S1'", Long.class);
        jdbc.update("INSERT INTO fan_active (fan_id, operation_id, silo_id, locked_at) VALUES (?, ?, ?, ?)",
                fanId, -999L, -999L, Timestamp.valueOf(LocalDateTime.now()));

        Exception caught = null;
        try {
            service.orderVentilation(ventilation("v-c3", "S1", "F1", 60.0, T1, T2));
        } catch (Exception ex) {
            caught = ex;
        }
        assertNotNull(caught, "风机被占用时通风令必须失败");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_operation WHERE request_id = 'v-c3'", Long.class),
                "风机未锁住时作业不得存在（同事务原子回滚）");
        assertEquals("ALARM", jdbc.queryForObject(
                "SELECT status FROM silo WHERE code = 'S1'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT ventilation_rounds FROM silo WHERE code = 'S1'", Integer.class));
    }

    /**
     * 同仓同 requestId 同参数并发：一个首次受理，另一个返回首次结果，作业窗仍只有一个。
     */
    @Test
    void concurrent_same_request_replays_single_result() throws Exception {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.createFan(fan("F1"));
        service.reportAlarm(alarm("a-c4", "S1", T1.minusHours(1), 18.0, 23.0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.orderVentilation(
                            ventilation("v-c4", "S1", "F1", 60.0, T1, T2)).operationId();
                }));
            }
            start.countDown();
            Long first = futures.get(0).get(30, TimeUnit.SECONDS);
            Long second = futures.get(1).get(30, TimeUnit.SECONDS);
            assertEquals(first, second, "并发同键重放必须返回同一作业（首次结果）");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_operation WHERE request_id = 'v-c4'", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'v-c4'", Long.class));
    }
}
