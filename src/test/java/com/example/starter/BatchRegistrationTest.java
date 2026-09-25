package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.api.ConflictException;
import com.example.starter.batch.BatchCreateRequest;
import com.example.starter.batch.BatchResponse;
import com.example.starter.domain.BatchStatus;
import org.junit.jupiter.api.Test;

/**
 * 产地报备：幂等重放、同键异参 409、并发唯一受理。
 */
class BatchRegistrationTest extends AbstractIntegrationTest {

    @Test
    void registerCreatesBatchInRegisteredStatus() {
        BatchResponse batch = registerBatch("reg-1", "山东寿光", "番茄");

        assertThat(batch.id()).isPositive();
        assertThat(batch.status()).isEqualTo(BatchStatus.REGISTERED);
        assertThat(batch.origin()).isEqualTo("山东寿光");
        assertThat(batch.createdAt()).isNotNull();
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        BatchResponse first = registerBatch("reg-2", "山东寿光", "番茄");
        BatchResponse replay = registerBatch("reg-2", "山东寿光", "番茄");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(batchRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        registerBatch("reg-3", "山东寿光", "番茄");

        assertThatThrownBy(() -> batchService.register(
                new BatchCreateRequest("reg-3", "云南昆明", "番茄", new BigDecimal("1000.00"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(batchRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationWithSameKeyAcceptedOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BatchResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return registerBatch("reg-concurrent", "山东寿光", "黄瓜");
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<BatchResponse> results = new ArrayList<>();
        for (Future<BatchResponse> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(results).allSatisfy(r -> assertThat(r.id()).isEqualTo(results.get(0).id()));
        assertThat(batchRepository.count()).isEqualTo(1);
    }
}
