package com.example.starter.trace;

import com.example.starter.trace.api.TraceDtos.ContributionItem;
import com.example.starter.trace.api.TraceDtos.RapidTestRequest;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.service.BusinessException;
import com.example.starter.trace.service.HarvestService;
import com.example.starter.trace.service.TestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发：同批同时刻只允许一条检测链，两个并发快检仅一个成功。
 */
class TestChainConcurrencyTest extends BaseTraceTest {

    @Autowired
    private HarvestService harvestService;

    @Autowired
    private TestService testService;

    @Test
    void 并发快检同批_仅一条检测链成功() throws Exception {
        Long apiaryId = jdbc.queryForObject(
                "SELECT id FROM apiary WHERE code = 'AP-001'", Long.class);
        Long batchId = harvestService.register(new RegisterHarvestBatchRequest("req-cc-0", "HB-CC1",
                "2026-洋槐", List.of(new ContributionItem(apiaryId, new BigDecimal("100.00"))))).id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        try {
            Future<?> futureA = executor.submit(() -> {
                ready.countDown();
                await(go);
                resultA.set(tryRapid(batchId, "req-cc-a", "SMP-CC-A"));
            });
            Future<?> futureB = executor.submit(() -> {
                ready.countDown();
                await(go);
                resultB.set(tryRapid(batchId, "req-cc-b", "SMP-CC-B"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<Object> results = List.of(resultA.get(), resultB.get());
        long successes = results.stream().filter(r -> !(r instanceof BusinessException)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof BusinessException e
                        && e.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rapid_test WHERE batch_id = ?",
                Integer.class, batchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_chain WHERE batch_id = ? AND status = 'OPEN'",
                Integer.class, batchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM harvest_batch WHERE id = ?",
                String.class, batchId)).isEqualTo("FROZEN");
    }

    private Object tryRapid(Long batchId, String requestId, String sampleNo) {
        try {
            return testService.rapidTest(batchId,
                    new RapidTestRequest(requestId, sampleNo, new BigDecimal("55.00")));
        } catch (BusinessException e) {
            return e;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
