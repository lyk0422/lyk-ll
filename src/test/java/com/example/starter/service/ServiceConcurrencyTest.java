package com.example.starter.service;

import com.example.starter.dto.IngestResponse;
import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务层并发边界（真实线程并发，数据库为真实 H2）：
 * <ul>
 *   <li>容量约束在高并发写入下不被突破：最终存活数不超过容量；</li>
 *   <li>相同 dedupKey 并发写入恰好一条 ACCEPTED，其余幂等 DEDUP。</li>
 * </ul>
 */
@TestPropertySource(properties = "forwarder.capacity=5")
class ServiceConcurrencyTest extends ForwarderTestBase {

    @BeforeEach
    void goOffline() {
        // 离线避免写入立即补发改变容量形态
        service.setOnline(false);
    }

    @Test
    void concurrentWritesNeverExceedCapacity() throws Exception {
        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<IngestResponse> responses = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    IngestResponse r = service.ingest("dev-" + (idx % 4), "/t", idx,
                            idx % 3, "key-" + idx, null);
                    responses.add(r);
                    if ("ACCEPTED".equals(r.outcome())) {
                        accepted.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // 离线场景下全部为 PENDING，每次写满都能驱逐最低优先级最旧记录后接受：
        // 30 次写入全部 ACCEPTED，最终恰好保留容量上限 5 条，累计驱逐 25 条。
        assertThat(accepted.get()).isEqualTo(threads);
        assertThat(messageRepository.countAll()).isEqualTo(5);
        assertThat(responses).allSatisfy(r -> assertThat(r.outcome()).isEqualTo("ACCEPTED"));
        assertThat(auditRepository.findByType(com.example.starter.domain.AuditType.EVICT))
                .hasSize(threads - 5);
        assertThat(auditRepository.findByType(com.example.starter.domain.AuditType.REJECT))
                .isEmpty();
    }

    @Test
    void concurrentSameDedupKeyAcceptedExactlyOnce() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger dedup = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    IngestResponse r = service.ingest("dev-1", "/t", 1, 5,
                            "concurrent-same", null);
                    if ("ACCEPTED".equals(r.outcome())) {
                        accepted.incrementAndGet();
                    } else if ("DEDUP".equals(r.outcome())) {
                        dedup.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(dedup.get()).isEqualTo(threads - 1);
        assertThat(messageRepository.findAll()).hasSize(1);
    }
}
