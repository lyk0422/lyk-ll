package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.api.ConflictException;
import com.example.starter.batch.BatchCreateRequest;
import com.example.starter.batch.BatchResponse;
import com.example.starter.domain.BatchStatus;
import com.example.starter.inspection.RapidTestRequest;
import org.junit.jupiter.api.Test;

/**
 * 并发边界：重复送检唯一约束、单检测链原子流转、并发报备唯一受理。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentRapidTestSameItemKeepsSingleRecord() throws Exception {
        BatchResponse batch = registerBatch("cc1-reg", "山东寿光", "番茄");
        sample("cc1-sam", batch.id(), "有机磷");

        int threads = 8;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(threads, i -> {
            try {
                inspectionService.rapidTest(batch.id(),
                        new RapidTestRequest("cc1-test-" + i, "有机磷", new BigDecimal("30")));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(rapidTestRepository.count()).isEqualTo(1);
        assertThat(batchRepository.findById(batch.id()).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);
    }

    @Test
    void concurrentChainsOnSameBatchKeepSingleChain() throws Exception {
        BatchResponse batch = registerBatch("cc2-reg", "山东寿光", "番茄");
        sample("cc2-sam1", batch.id(), "有机磷");
        sample("cc2-sam2", batch.id(), "菊酯类");
        String[] items = {"有机磷", "菊酯类"};

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(2, i -> {
            try {
                inspectionService.rapidTest(batch.id(),
                        new RapidTestRequest("cc2-test-" + i, items[i], new BigDecimal("30")));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        });

        // 同一批次同时刻只允许一条检测链：败方事务回滚，不留检测记录
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(rapidTestRepository.count()).isEqualTo(1);
        assertThat(batchRepository.findById(batch.id()).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);
    }

    @Test
    void concurrentRegistrationDifferentParamsSameKeyYieldsSingleBatch() throws Exception {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(2, i -> {
            try {
                batchService.register(new BatchCreateRequest("cc3-reg",
                        i == 0 ? "山东寿光" : "云南昆明", "番茄", new BigDecimal("1000.00")));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(batchRepository.count()).isEqualTo(1);
    }

    private void runConcurrently(int threads, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                task.run(index);
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    private interface ThrowingTask {
        void run(int index) throws Exception;
    }
}
