package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.repository.BatchRepository;
import com.example.starter.fermentation.service.BatchService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发入罐测试：同一发酵罐同时刻仅允许一个进行中批次，并发提交由数据库唯一约束保证唯一受理。
 */
@SpringBootTest
class BatchConcurrencyTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("8 线程并发入罐同一发酵罐：仅一个受理（201），其余 409，库中仅一条进行中批次")
    void concurrentFillingOnlyOneAccepted() throws Exception {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM yeast_recovery");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM merge_receipt_source");
        jdbc.update("DELETE FROM merge_receipt");
        jdbc.update("DELETE FROM source_volume");
        jdbc.update("DELETE FROM receiving_tank");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM fermentation_batch");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
                try {
                    StoredResponse response = batchService.createBatch(new CreateBatchRequest(
                            "req-concurrent-" + n, "B-CONC-" + n, "TANK-1", "LAGER-CURVE",
                            BaseFermentationTest.T0));
                    if (response.status() == 201) {
                        created.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if (e.status().value() == 409) {
                        conflicted.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");

        assertEquals(1, created.get(), "并发入罐应仅一个受理");
        assertEquals(threads - 1, conflicted.get(), "其余并发请求应返回 409");
        assertEquals(0, unexpected.get(), "不应出现其他结果");
        assertEquals(1, batchRepository.countActiveByTankKey("TANK-1"),
                "库中应仅一条该罐进行中批次");
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM fermentation_batch",
                Integer.class);
        assertEquals(1, total, "库中应仅创建一条批次");
    }
}
