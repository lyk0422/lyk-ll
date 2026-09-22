package com.example.starter.service;

import com.example.starter.domain.Detection;
import com.example.starter.domain.DetectionOutcome;
import com.example.starter.domain.VersionConflictException;
import com.example.starter.testsupport.TestDataCleaner;
import com.example.starter.web.dto.AssociateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试（真实多线程 + H2）：
 * 同一 detectionId 并发提交必须恰好处理一次；同一轨迹的并发显式关联
 * 由乐观锁保证只有一个成功，其余快速失败且轨迹状态一致。
 */
@SpringBootTest
class TrackConcurrencyH2Test {

    @Autowired
    private TrackLifecycleService service;

    @Autowired
    private TestDataCleaner cleaner;

    @BeforeEach
    void cleanData() {
        cleaner.clean();
    }

    private Detection det(String id, long t, double x, double conf) {
        return new Detection(id, t, "cam-1", "car", x, 0.0, 0.0, conf);
    }

    @Test
    void concurrentSameDetectionProcessedExactlyOnce() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssociateResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                Callable<AssociateResponse> task = () -> {
                    start.await();
                    return service.associate(det("same-id", 1000L, 0, 0.9),
                            null, null, null);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            int created = 0;
            int duplicate = 0;
            for (Future<AssociateResponse> f : futures) {
                AssociateResponse response = f.get(30, TimeUnit.SECONDS);
                if (response.outcome() == DetectionOutcome.CREATED) {
                    created++;
                } else if (response.outcome() == DetectionOutcome.DUPLICATE) {
                    duplicate++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(duplicate).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 恰好一条轨迹、版本停留在首观测后的 2，没有重复创建或重复观测。
        assertThat(service.listTracks()).hasSize(1);
        assertThat(service.getTrack("T-000001").version()).isEqualTo(2L);
    }

    @Test
    void concurrentExplicitAssociationsCasSerializesUpdates() throws Exception {
        service.associate(det("d0", 1000L, 0, 0.9), null, null, null);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 1; i <= threads; i++) {
                long ts = 2000L + i;
                double x = 0.1 * i;
                Callable<Object> task = () -> {
                    start.await();
                    // 所有线程都基于同一初始版本 2 做 CAS：最多一个成功。
                    return service.associate(det("d-" + ts, ts, x, 0.9),
                            "T-000001", 2L, 5.0);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            int success = 0;
            int conflicts = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (Exception ex) {
                    assertThat(ex).cause().isInstanceOf(VersionConflictException.class);
                    conflicts++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 只有一次有效观测落地：版本恰好 3。
        assertThat(service.getTrack("T-000001").version()).isEqualTo(3L);
    }
}
