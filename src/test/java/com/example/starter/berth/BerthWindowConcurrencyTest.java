package com.example.starter.berth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发幂等测试：真实并发提交相同 requestId，数据库唯一约束保证只允许一次插入成功且响应一致。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BerthWindowConcurrencyTest {

    @Autowired
    private BerthWindowService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
    }

    @Test
    void 并发同键同参_只允许一次成功且响应一致() throws Exception {
        int threads = 8;
        CreateBerthWindowRequest request = new CreateBerthWindowRequest("CC-1", "VESSEL-CC", 1L,
                Instant.parse("2026-09-25T01:00:00Z"), Instant.parse("2026-09-25T03:00:00Z"),
                new BigDecimal("10.0"), 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<BerthWindowResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return service.create(request);
            }));
        }
        gate.countDown();
        List<BerthWindowResponse> responses = new ArrayList<>();
        for (Future<BerthWindowResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long distinctIds = responses.stream().map(BerthWindowResponse::id).distinct().count();
        assertEquals(1, distinctIds, "并发同键必须返回同一窗口");
        responses.forEach(r -> assertEquals(responses.get(0), r));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM berth_window WHERE request_id = 'CC-1'", Integer.class);
        assertEquals(1, count, "并发同键只允许一条记录");
    }

    @Test
    void 并发同键异参_仅一个指纹落地() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    service.create(new CreateBerthWindowRequest("CC-2", "VESSEL-" + n, 1L,
                            Instant.parse("2026-09-25T01:00:00Z"), Instant.parse("2026-09-25T03:00:00Z"),
                            new BigDecimal("10.0"), 1));
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            }));
        }
        gate.countDown();
        int success = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, success, "同键异参并发只允许一次成功");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM berth_window WHERE request_id = 'CC-2'", Integer.class);
        assertEquals(1, count);
    }
}
