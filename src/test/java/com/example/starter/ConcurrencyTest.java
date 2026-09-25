package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ApiRequests.DepartureRequest;
import com.example.starter.api.dto.ApiRequests.SettlementRequest;
import com.example.starter.api.dto.ApiRequests.SpeciesQuantity;
import com.example.starter.service.SettlementService;
import com.example.starter.service.VoyageService;
import com.example.starter.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：两航次并发核销不得合计超配额；并发出港申报唯一受理。
 * 通过真实线程并发调用服务层（各自独立事务），以闩锁同步起跑并设置超时。
 */
class ConcurrencyTest extends ApiTestSupport {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private VoyageService voyageService;

    /** 并发执行两个任务并等待结果（超时 15 秒）。 */
    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrapFirst = () -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS), "并发起跑超时");
                return first.call();
            };
            Callable<T> wrapSecond = () -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS), "并发起跑超时");
                return second.call();
            };
            Future<T> f1 = pool.submit(wrapFirst);
            Future<T> f2 = pool.submit(wrapSecond);
            assertTrue(ready.await(15, TimeUnit.SECONDS), "线程就绪超时");
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "线程池回收超时");
        }
    }

    @Test
    @DisplayName("并发核销两航次：合计不得超配额，恰一笔成功")
    void concurrentSettlementsNeverExceedQuota() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageA = departLogArrive("cc-a", 1, "COD", "600", "2026-03-02T10:00:00Z");
        long voyageB = departLogArrive("cc-b", 1, "COD", "500", "2026-04-02T10:00:00Z");

        Callable<Object> settleA = () -> {
            try {
                return settlementService.settle(voyageA, new SettlementRequest("cc-a-set"));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> settleB = () -> {
            try {
                return settlementService.settle(voyageB, new SettlementRequest("cc-b-set"));
            } catch (ApiException e) {
                return e;
            }
        };
        List<Object> outcomes = runConcurrently(settleA, settleB);

        long successes = outcomes.stream().filter(o -> !(o instanceof ApiException)).count();
        long quotaExceeded = outcomes.stream()
                .filter(o -> o instanceof ApiException e
                        && e.status().value() == 422 && "QUOTA_EXCEEDED".equals(e.code()))
                .count();
        assertEquals(1, successes, "恰一笔核销成功: " + outcomes);
        assertEquals(1, quotaExceeded, "另一笔因配额不足被拒: " + outcomes);

        // 最终余额：600 或 500，合计绝不超配额 1000
        BigDecimal used = fixtures.usedQuota(1, "COD", 2026);
        assertTrue(used.compareTo(new BigDecimal("1000")) <= 0, "已核销量不得超配额: " + used);
        assertEquals(1, fixtures.settlementCount(voyageA) + fixtures.settlementCount(voyageB));
    }

    @Test
    @DisplayName("并发出港申报：同一渔船仅一笔受理")
    void concurrentDeparturesOnlyOneAccepted() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        Callable<Object> departA = () -> {
            try {
                return voyageService.depart(new DepartureRequest("cd-a", 1L,
                        Instant.parse("2026-03-01T08:00:00Z"), "A1",
                        List.of(new SpeciesQuantity("COD", new BigDecimal("100")))));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> departB = () -> {
            try {
                return voyageService.depart(new DepartureRequest("cd-b", 1L,
                        Instant.parse("2026-03-01T08:00:00Z"), "A1",
                        List.of(new SpeciesQuantity("COD", new BigDecimal("100")))));
            } catch (ApiException e) {
                return e;
            }
        };
        List<Object> outcomes = runConcurrently(departA, departB);

        long successes = outcomes.stream().filter(o -> !(o instanceof ApiException)).count();
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof ApiException e && e.status().value() == 409)
                .count();
        assertEquals(1, successes, "恰一笔出港申报受理: " + outcomes);
        assertEquals(1, conflicts, "另一笔因进行中航次被拒: " + outcomes);
        assertEquals(1, fixtures.voyageCount(1));
    }
}
