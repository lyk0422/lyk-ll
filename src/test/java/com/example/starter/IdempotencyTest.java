package com.example.starter;

import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.MonitoringService;
import com.example.starter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写操作幂等：同键同参返回首次结果，同键异参 409，并发下同键仅生效一次。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private IdempotencyRepository idempotencyRepository;

    private String canonical(String zoneCode, String doValue) {
        return "zoneCode=" + zoneCode + "&dissolvedOxygen=" + doValue
                + "&ammoniaNitrogen=0.10&temperature=20.0&sampledAt=" + T0;
    }

    private String ingestOnce(String requestId, String zoneCode, String doValue) {
        return idempotencyService.execute(requestId, "sample", canonical(zoneCode, doValue),
                () -> monitoringService.ingest(zoneCode, requestId, new BigDecimal(doValue),
                        new BigDecimal("0.10"), new BigDecimal("20.0"), T0).toString());
    }

    @Test
    void 同键同参返回首次结果且不重复写入() {
        String first = ingestOnce("req-idem-1", "ZONE-A", "6.0");
        String replayed = ingestOnce("req-idem-1", "ZONE-A", "6.0");
        assertEquals(first, replayed);
        Long samples = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM water_sample WHERE zone_id = ?", Long.class, zoneId("ZONE-A"));
        assertEquals(1L, samples);
        assertTrue(idempotencyRepository.find("req-idem-1").isPresent());
    }

    @Test
    void 同键异参返回409() {
        ingestOnce("req-idem-2", "ZONE-A", "6.0");
        BusinessException e = assertThrows(BusinessException.class,
                () -> ingestOnce("req-idem-2", "ZONE-A", "7.0"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        Long samples = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM water_sample WHERE zone_id = ?", Long.class, zoneId("ZONE-A"));
        assertEquals(1L, samples, "冲突请求不得产生副作用");
    }

    @Test
    void 并发同键同参仅首次生效() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return ingestOnce("req-idem-concurrent", "ZONE-B", "6.5");
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<String> future : futures) {
            bodies.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        String first = bodies.get(0);
        for (String body : bodies) {
            assertEquals(first, body, "并发重放应返回首次结果");
        }
        Long samples = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM water_sample WHERE zone_id = ?", Long.class, zoneId("ZONE-B"));
        assertEquals(1L, samples, "并发下同键请求只能写入一条采样");
        Long idemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-idem-concurrent'",
                Long.class);
        assertEquals(1L, idemRows);
    }

    @Test
    void 不同键互不影响() {
        ingestOnce("req-idem-3a", "ZONE-C", "6.0");
        ingestOnce("req-idem-3b", "ZONE-C", "4.0");
        Long samples = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM water_sample WHERE zone_id = ?", Long.class, zoneId("ZONE-C"));
        assertEquals(2L, samples);
    }
}
