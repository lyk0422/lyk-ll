package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.dto.BrightnessRequest;
import com.example.starter.dto.DimRequest;
import com.example.starter.dto.DimResult;
import com.example.starter.exception.BizException;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.RecordRepository;
import com.example.starter.repo.WriteRequestRepository;
import com.example.starter.service.DimmingService;

/**
 * 幂等与并发测试：同键同参返回首次结果、同键异参 409、
 * 并发同键仅执行一次、同一回路同时刻只允许一条执行中指令。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tl_test_idem;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private DimmingService service;

    @Autowired
    private CommandRepository commandRepo;

    @Autowired
    private RecordRepository recordRepo;

    @Autowired
    private WriteRequestRepository writeRequestRepo;

    @Test
    void 同键同参返回首次结果() {
        DimResult first = service.dimManually(1L, new DimRequest("r-idem-1", 4));
        DimResult second = service.dimManually(1L, new DimRequest("r-idem-1", 4));
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.fromLevel(), second.fromLevel());
        assertEquals(first.toLevel(), second.toLevel());
        assertEquals(first.createdAt(), second.createdAt());
        assertEquals(1, commandRepo.countByRequestId("r-idem-1"));
        assertEquals(1, recordRepo.countByCircuit(1L));
        assertEquals(1, writeRequestRepo.count());
    }

    @Test
    void 同键异参返回409且不产生副作用() {
        service.dimManually(1L, new DimRequest("r-idem-c", 4));
        BizException ex = assertThrows(BizException.class,
                () -> service.dimManually(1L, new DimRequest("r-idem-c", 2)));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals(4, service.getCircuit(1L).currentLevel());
        assertEquals(1, recordRepo.countByCircuit(1L));
    }

    @Test
    void 并发同键同参仅执行一次() throws Exception {
        int threads = 8;
        List<DimResult> results = runConcurrently(threads,
                i -> service.dimManually(1L, new DimRequest("r-conc-same", 4)));
        assertEquals(threads, results.size());
        assertEquals(1, results.stream().filter(r -> !r.replayed()).count());
        assertTrue(results.stream().allMatch(r -> r.toLevel() != null && r.toLevel() == 4));
        assertEquals(1, commandRepo.countByRequestId("r-conc-same"));
        assertEquals(1, recordRepo.countByCircuit(1L));
        assertEquals(1, writeRequestRepo.count());
        assertEquals(4, service.getCircuit(1L).currentLevel());
    }

    @Test
    void 并发异键人工调光稳定期内仅首条执行() throws Exception {
        int threads = 6;
        List<DimResult> results = runConcurrently(threads,
                i -> service.dimManually(1L, new DimRequest("r-conc-m" + i, 4)));
        long executed = results.stream().filter(r -> DimResult.STATUS_EXECUTED.equals(r.status())).count();
        assertEquals(1, executed);
        // 同一回路同时刻只允许一条执行中指令：指令与留痕均只有一条
        assertEquals(1, commandRepo.countByCircuit(1L));
        assertEquals(1, recordRepo.countByCircuit(1L));
        assertEquals(4, service.getCircuit(1L).currentLevel());
    }

    @Test
    void 并发亮度升档串行执行至最高档() throws Exception {
        // 亮度升档不受稳定期限制：3→4→5 两次有效升档，其余恰为最高档不再触发
        int threads = 6;
        List<DimResult> results = runConcurrently(threads,
                i -> service.checkBrightness(1L, new BrightnessRequest("r-conc-b" + i, 100)));
        long executed = results.stream().filter(r -> DimResult.STATUS_EXECUTED.equals(r.status())).count();
        assertEquals(2, executed);
        assertEquals(2, commandRepo.countByCircuit(1L));
        assertEquals(2, recordRepo.countByCircuit(1L));
        assertEquals(5, service.getCircuit(1L).currentLevel());
    }

    /** 协调真实并发：所有线程就绪后同时放行，带超时，断言结果与最终数据。 */
    private List<DimResult> runConcurrently(int threads, ThrowingCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DimResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "并发放行超时");
                    return call.run(index);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发就绪超时");
            start.countDown();
            List<DimResult> results = new ArrayList<>();
            for (Future<DimResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程池终止超时");
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        DimResult run(int index) throws Exception;
    }
}
