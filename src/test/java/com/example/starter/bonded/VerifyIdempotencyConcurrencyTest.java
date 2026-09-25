package com.example.starter.bonded;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.VerifyItemRequest;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.service.TransferService;
import com.example.starter.bonded.web.ApiBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核销批次级幂等的并发边界。
 */
class VerifyIdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private TransferService service;

    @Test
    @DisplayName("并发相同 batchRequestId 同参核销：只执行一次，其余回放首次结果，库存只变动一次")
    void concurrentSameBatchIdExecutedOnce() throws Exception {
        service.apply(new ApplyTransferRequest("REQ-I1", "ENT-001", "WH-A", "WH-B",
                List.of(new ItemRequest("HS-1001", 100L, new BigDecimal("10")))));
        service.endorse("REQ-I1");
        service.depart("REQ-I1");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.verify("REQ-I1", new VerifyRequest("B-SAME",
                        List.of(new VerifyItemRequest("HS-1001", 30L)))).replayed();
            }));
        }
        start.countDown();
        int first = 0;
        int replayed = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                replayed++;
            } else {
                first++;
            }
        }
        pool.shutdownNow();
        assertThat(first).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        var view = service.get("REQ-I1");
        assertThat(view.status()).isEqualTo("IN_TRANSIT");
        assertThat(view.items().get(0).verifiedQty()).isEqualTo(30L);
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 70L);
    }

    @Test
    @DisplayName("并发相同 batchRequestId 但参数不同：唯一约束后第二个收到 409，库存仅按首次参数变动")
    void concurrentSameBatchIdDifferentParamsConflict() throws Exception {
        service.apply(new ApplyTransferRequest("REQ-I2", "ENT-001", "WH-A", "WH-B",
                List.of(new ItemRequest("HS-1001", 100L, new BigDecimal("10")))));
        service.endorse("REQ-I2");
        service.depart("REQ-I2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            service.verify("REQ-I2", new VerifyRequest("B-DIFF",
                    List.of(new VerifyItemRequest("HS-1001", 30L))));
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            service.verify("REQ-I2", new VerifyRequest("B-DIFF",
                    List.of(new VerifyItemRequest("HS-1001", 31L))));
        });
        start.countDown();
        int conflict = 0;
        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApiBusinessException ex && ex.getStatus().value() == 409) {
                    conflict++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();
        assertThat(conflict).isEqualTo(1);
        // 获胜方随机（30 或 31 先提交），但必须恰有一个生效且库存与获胜方一致
        long verified = service.get("REQ-I2").items().get(0).verifiedQty();
        assertThat(verified).isIn(30L, 31L);
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 100L - verified);
    }
}
