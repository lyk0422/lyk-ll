package com.example.starter.sensor.service;

import com.example.starter.sensor.api.IngestMessageRequest;
import com.example.starter.sensor.api.IngestMessageResponse;
import com.example.starter.sensor.domain.IngestStatus;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.support.AbstractH2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摄取与帧构建并发边界测试：真实多线程并发写入 H2，验证串行化、
 * 无重复主键异常、无消息丢失，且同一消息 ID 并发投递只有一次 ACCEPTED。
 */
class SyncConcurrencyH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private SyncService syncService;

    @Test
    void concurrentIngestIsSerializableAndNoLostMessages() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String id = "lidar-" + idx + "-" + i;
                            // 全部消息时戳落在 0..24ms，远小于 100ms 迟到容忍，
                            // 任意并发交错都不会被判迟到
                            long ts = 1_000_000L * i;
                            IngestMessageResponse resp = syncService.ingest(SensorType.LIDAR,
                                    new IngestMessageRequest(id, ts, null));
                            if (resp.status() != IngestStatus.ACCEPTED) {
                                throw new IllegalStateException("期望 ACCEPTED，实际 " + resp.status());
                            }
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sensor_message WHERE sensor='LIDAR'", Integer.class);
        assertThat(count).isEqualTo(threads * perThread);
        // 水位线为最大事件时戳
        long expectedMax = 1_000_000L * (perThread - 1);
        assertThat(syncService.watermarkOf(SensorType.LIDAR)).isEqualTo(expectedMax);
    }

    @Test
    void concurrentDuplicateSubmissionYieldsSingleAcceptance() throws Exception {
        int copies = 10;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestMessageResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return syncService.ingest(SensorType.CAMERA,
                            new IngestMessageRequest("same-id", 5_000_000L, null));
                }));
            }
            start.countDown();
            int accepted = 0;
            int duplicate = 0;
            for (Future<IngestMessageResponse> f : futures) {
                IngestMessageResponse r = f.get(30, TimeUnit.SECONDS);
                if (r.status() == IngestStatus.ACCEPTED) {
                    accepted++;
                } else if (r.status() == IngestStatus.DUPLICATE) {
                    duplicate++;
                }
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(duplicate).isEqualTo(copies - 1);
        } finally {
            pool.shutdownNow();
        }

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sensor_message WHERE sensor='CAMERA' AND message_id='same-id'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
