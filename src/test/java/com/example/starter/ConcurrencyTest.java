package com.example.starter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.example.starter.service.BatchService;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界测试：并发申报唯一受理、同键并发重放结果一致、同一批次同时刻只允许一条检验链。
 * 通过真实并发线程 + H2 唯一约束验证，断言最终结果与落库数据。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private BatchService service;

    @Test
    @DisplayName("并发申报同一批次：唯一受理，其余冲突")
    void concurrentDeclareSameBatchOnlyOneAccepted() throws Exception {
        DeclareRequest a = new DeclareRequest("req-a", "B-CONC", "PIG", "合成养殖场", List.of("H1"));
        DeclareRequest b = new DeclareRequest("req-b", "B-CONC", "PIG", "合成养殖场", List.of("H1"));

        List<Outcome<DeclareResponse>> outcomes = runConcurrently(
                () -> service.declare(a), () -> service.declare(b));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error() != null && o.error().status() == 409).count();
        assertEquals(1, successes, "并发申报须唯一受理");
        assertEquals(1, conflicts, "未受理方须返回 409 冲突");
        assertEquals(1, countRows("slaughter_batch"));
        assertEquals(1, countRows("head_ledger"));
    }

    @Test
    @DisplayName("同键同参并发申报：均返回首次结果且只建档一次")
    void concurrentSameRequestIdReturnsFirstResult() throws Exception {
        DeclareRequest request = new DeclareRequest("req-same", "B-CONC2", "PIG", "合成养殖场",
                List.of("H1", "H2"));

        List<Outcome<DeclareResponse>> outcomes = runConcurrently(
                () -> service.declare(request), () -> service.declare(request));

        assertTrue(outcomes.stream().allMatch(o -> o.error() == null),
                "同键同参并发须均成功重放");
        DeclareResponse first = outcomes.get(0).value();
        DeclareResponse second = outcomes.get(1).value();
        assertEquals(first, second);
        assertEquals(1, countRows("slaughter_batch"));
        assertEquals(2, countRows("head_ledger"));
        assertEquals(1, countRows("idempotency_record"));
    }

    @Test
    @DisplayName("同一批次同时刻只允许一条检验链：并发宰前检疫唯一放行")
    void concurrentPreQuarantineSingleChain() throws Exception {
        declareOk("d-1", "B-CHAIN", List.of("H1"));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> service.preQuarantine("B-CHAIN", new PreQuarantineRequest("pq-a", "检疫员甲")),
                () -> service.preQuarantine("B-CHAIN", new PreQuarantineRequest("pq-b", "检疫员乙")));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error() != null && o.error().status() == 409).count();
        assertEquals(1, successes, "同一批次同时刻只允许一条检验链");
        assertEquals(1, conflicts);
        assertEquals("PRE_QUARANTINED", getView("B-CHAIN").status());
    }

    // ---------- 并发辅助 ----------

    /** 并发执行结果：成功值或业务异常。 */
    private record Outcome<T>(T value, ApiException error) {
    }

    /**
     * 真实并发执行两个操作：统一栅栏同时起跑，15 秒超时，返回双方结果。
     */
    private <T> List<Outcome<T>> runConcurrently(Supplier<T> first, Supplier<T> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Outcome<T>> taskFirst = () -> awaitAndRun(ready, start, first);
            Callable<Outcome<T>> taskSecond = () -> awaitAndRun(ready, start, second);
            Future<Outcome<T>> futureFirst = pool.submit(taskFirst);
            Future<Outcome<T>> futureSecond = pool.submit(taskSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            Outcome<T> outcomeFirst = futureFirst.get(15, TimeUnit.SECONDS);
            Outcome<T> outcomeSecond = futureSecond.get(15, TimeUnit.SECONDS);
            assertNotNull(outcomeFirst);
            assertNotNull(outcomeSecond);
            return List.of(outcomeFirst, outcomeSecond);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发线程未在超时内结束");
        }
    }

    private static <T> Outcome<T> awaitAndRun(CountDownLatch ready, CountDownLatch start,
                                              Supplier<T> action) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome<>(action.get(), null);
        } catch (ApiException e) {
            return new Outcome<>(null, e);
        }
    }
}
