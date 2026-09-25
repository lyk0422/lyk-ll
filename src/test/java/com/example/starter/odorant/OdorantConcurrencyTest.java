package com.example.starter.odorant;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浓度越限并发边界：同一时刻两个不同 requestId 并发上报越限，
 * 经 valve_state 行锁串行计数，恰一个请求触发锁阀，不允许重复锁/重复工单。
 */
class OdorantConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(12, 0);

    @Autowired
    private OdorantService odorantService;

    @Test
    @SuppressWarnings("unchecked")
    void concurrentBreachReportsLockExactlyOnce() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<OdorantDetectResponse>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                return odorantService.detect(new OdorantDetectRequest("ST01", "OC-" + idx,
                        T.plusMinutes(idx), new BigDecimal("100"), new BigDecimal("15.000")));
            });
        }
        start.countDown();

        int locks = 0;
        int maxBreach = 0;
        for (Future<OdorantDetectResponse> f : futures) {
            OdorantDetectResponse resp = f.get(15, TimeUnit.SECONDS);
            if (resp.injectionLocked()) {
                locks++;
            }
            maxBreach = Math.max(maxBreach, resp.breachCount());
        }
        pool.shutdown();

        assertEquals(1, locks, "并发越限只允许恰一个请求锁阀");
        assertEquals(2, maxBreach, "行锁串行后连续越限计数必须达到2");
        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND action = 'LOCK' "
                + "AND status = 'PENDING' AND trigger_request_id IS NOT NULL"), "触发锁阀单恰一张");
        assertTrue(queryBoolean("SELECT injection_locked FROM valve_state WHERE station_id = 1"));
    }

    private boolean queryBoolean(String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class));
    }
}
