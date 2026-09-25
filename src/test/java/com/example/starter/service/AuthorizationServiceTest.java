package com.example.starter.service;

import com.example.starter.domain.AuthorizationStatus;
import com.example.starter.domain.ZoneAuthorization;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重叠区授权规则测试：半开区间冲突、超载拒绝、幂等、并发唯一授权、释放。
 */
class AuthorizationServiceTest extends BaseIntegrationTest {

    private static final Instant T0 = TestClockConfig.BASE.plusSeconds(10 * 3600);

    @Autowired
    private AuthorizationService service;

    @Test
    void halfOpenIntervalAllowsEndpointAdjacentSlots() {
        service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        // 端点相接不算冲突：[11:00,12:00) 与 [09:00,10:00) 均应成功
        var next = service.request("ZONE-1", "req-2", "CRANE-B",
                T0.plusSeconds(3600), T0.plusSeconds(7200), new BigDecimal("5"));
        var prev = service.request("ZONE-1", "req-3", "CRANE-B",
                T0.minusSeconds(3600), T0, new BigDecimal("5"));
        assertFalse(next.replayed());
        assertFalse(prev.replayed());
        assertEquals(3, count("zone_authorization"));
    }

    @Test
    void overlappingWindowRejectedWith409() {
        service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        ApiException e = assertThrows(ApiException.class, () -> service.request("ZONE-1", "req-2",
                "CRANE-B", T0.plusSeconds(1800), T0.plusSeconds(5400), new BigDecimal("5")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("ZONE_CONFLICT", e.code());
        assertEquals(1, count("zone_authorization"));
    }

    @Test
    void overloadRejectedWith422AndNotPersisted() {
        ApiException e = assertThrows(ApiException.class, () -> service.request("ZONE-1", "req-1",
                "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("8.5")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals("OVERLOAD", e.code());
        assertEquals(0, count("zone_authorization"));
        // 超载拒绝不占用区域：同 requestId 修正参数后可重新申请
        var ok = service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("8"));
        assertFalse(ok.replayed());
    }

    @Test
    void idempotentSameKeySameParamsReturnsFirstResult() {
        var first = service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        var replay = service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        assertTrue(replay.replayed());
        assertEquals(first.authorization().id(), replay.authorization().id());
        assertEquals(1, count("zone_authorization"));
    }

    @Test
    void idempotentSameKeyDifferentParamsReturns409AndDoesNotOccupyZone() {
        service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        ApiException e = assertThrows(ApiException.class, () -> service.request("ZONE-1", "req-1",
                "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("6")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("IDEMPOTENCY_MISMATCH", e.code());
        // 异参请求不占用区域：其他塔机用新 requestId 同时段申请应 409（被首次授权占用而非异参请求）
        ApiException conflict = assertThrows(ApiException.class, () -> service.request("ZONE-1", "req-2",
                "CRANE-B", T0, T0.plusSeconds(3600), new BigDecimal("5")));
        assertEquals("ZONE_CONFLICT", conflict.code());
        assertEquals(1, count("zone_authorization"));
    }

    @Test
    void concurrentRequestsSameZoneSameWindowAllowSingleWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    service.request("ZONE-1", "req-" + n, "CRANE-A",
                            T0, T0.plusSeconds(3600), new BigDecimal("5"));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, success.get(), "并发申请同一重叠区同时段只允许一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, count("zone_authorization"));
    }

    @Test
    void releaseIsIdempotentAndFreesTheSlot() {
        var auth = service.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        long id = auth.authorization().id();
        ZoneAuthorization released = service.release(id, AuthorizationService.ReleaseAction.COMPLETE);
        assertEquals(AuthorizationStatus.COMPLETED, released.status());
        // 重复释放幂等返回当前状态
        ZoneAuthorization again = service.release(id, AuthorizationService.ReleaseAction.CANCEL);
        assertEquals(AuthorizationStatus.COMPLETED, again.status());
        // 释放后同时段可再授权
        var next = service.request("ZONE-1", "req-2", "CRANE-B", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        assertFalse(next.replayed());
    }
}
