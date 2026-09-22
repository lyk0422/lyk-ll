package com.example.starter.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.sync.domain.IngestOutcome;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 并发边界测试：同一消息并发接收幂等，同一目标时刻并发提交只成功一次。
 */
class ConcurrencyTest extends AbstractSyncTest {

    @Autowired
    private SensorSyncService sensorSyncService;

    @Autowired
    private FusionService fusionService;

    @Test
    void concurrentIngestOfSameMessageAcceptsExactlyOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return sensorSyncService.ingest(SensorType.LIDAR, "lidar-race", TARGET);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int accepted = 0;
        int duplicates = 0;
        for (Future<IngestOutcome> future : futures) {
            IngestOutcome outcome = future.get(10, TimeUnit.SECONDS);
            if (outcome.isAccepted()) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        pool.shutdownNow();

        assertThat(accepted).isEqualTo(1);
        assertThat(duplicates).isEqualTo(threads - 1);
        assertThat(messageCount(SensorType.LIDAR, "lidar-race")).isEqualTo(1);
    }

    @Test
    void concurrentCommitOfSameTargetCommitsExactlyOnce() throws Exception {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET);
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-1", TARGET);
        sensorSyncService.ingest(SensorType.IMU, "imu-1", TARGET);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> commitTask = () -> {
            ready.countDown();
            start.await();
            try {
                fusionService.commit(TARGET);
                return "OK";
            } catch (SyncException e) {
                return e.code().name();
            }
        };
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(commitTask));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn("FRAME_ALREADY_COMMITTED"::equals).hasSize(threads - 1);
        assertThat(frameCount(TARGET)).isEqualTo(1);
        Integer inputRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fusion_frame_input", Integer.class);
        assertThat(inputRows).isEqualTo(4);
    }
}
