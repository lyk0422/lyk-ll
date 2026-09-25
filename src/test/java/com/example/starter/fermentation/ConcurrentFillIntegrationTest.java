package com.example.starter.fermentation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一发酵罐并发入罐唯一受理：数据库 active_tank_key 唯一索引保证只有一个批次成功。
 */
class ConcurrentFillIntegrationTest extends AbstractIntegrationTest {

    @Test
    void concurrentFillOnSameTankAcceptsExactlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        String body = json(TestData.createBatch(
                                "B-C" + index, "TANK-CONC", TestData.T0));
                        int status = mockMvc.perform(
                                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                                .post("/api/fermentation/batches")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn().getResponse().getStatus();
                        if (status == 201) {
                            created.incrementAndGet();
                        } else if (status == 409) {
                            conflict.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(other.get()).isZero();

        Integer batchCount = jdbc.sql(
                        "SELECT COUNT(*) FROM fermentation_batch b JOIN fermentation_tank t ON t.id = b.tank_id "
                                + "WHERE t.tank_code = 'TANK-CONC'")
                .query(Integer.class).single();
        assertThat(batchCount).isEqualTo(1);
    }

    @Test
    void secondSequentialFillOnBusyTankConflictsButNewTankAccepted() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-S1", "TANK-S", Instant.parse("2026-09-01T00:00:00Z")))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-S2", "TANK-S", Instant.parse("2026-09-02T00:00:00Z")))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-S3", "TANK-S2", Instant.parse("2026-09-02T00:00:00Z")))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }
}
