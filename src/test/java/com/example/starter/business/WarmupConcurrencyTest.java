package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.WarmupStageSpec;
import com.example.starter.api.dto.WarmupTicketCreateRequest;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.WarmupService;

/**
 * 升温票 H2 真实并发边界：同窑并发创建只允许一张执行中升温票。
 */
class WarmupConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private WarmupService service;

    @Test
    void concurrent_create_same_kiln_accepts_only_one_ticket() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger activeConflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String reqId = String.format("REQ-WC-%03d", i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.create(new WarmupTicketCreateRequest(reqId, KILN, "CURVE-V1",
                            List.of(new WarmupStageSpec(new BigDecimal("600.00"), 60))));
                    accepted.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCategory() == RejectCategory.WARMUP_TICKET_ACTIVE) {
                        activeConflict.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(activeConflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warmup_ticket WHERE kiln_id = ? AND status = 'EXECUTING'",
                Integer.class, KILN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_warmup WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }
}
