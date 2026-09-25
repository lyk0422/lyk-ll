package com.example.starter.business;

import com.example.starter.domain.RequestType;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等并发边界：同键并发时若先到请求失败并释放受理记录，
 * 后到请求应重新抢占并成功，而不是空转到超时。
 */
class IdempotencyConcurrentFallbackTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyExecutor executor;

    @Test
    void firstFailingRequestReleasesClaim_secondSucceeds() throws Exception {
        long stationId = createStation("ST-ICF", 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFail = new CountDownLatch(1);
        AtomicReference<String> winner = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> executor.execute(
                    "icf-req-1", RequestType.ADJUST, "fp-1", stationId, String.class,
                    () -> {
                        firstStarted.countDown();
                        try {
                            allowFirstToFail.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw ApiException.unprocessable("SIMULATED_FAIL", "先到请求业务失败");
                    }));

            assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // 后到请求此时应进入等待
            Future<String> second = pool.submit(() -> executor.execute(
                    "icf-req-1", RequestType.ADJUST, "fp-1", stationId, String.class,
                    () -> {
                        winner.set("second");
                        return "OK";
                    }));

            allowFirstToFail.countDown();

            // 先到失败
            assertThatThrown(first);
            // 后到在前者释放后重新抢占并成功
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("OK");
            assertThat(winner).hasValue("second");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertThatThrown(Future<?> future) {
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("期望先到请求抛出异常");
        } catch (java.util.concurrent.ExecutionException e) {
            // 预期：cause 为 ApiException(SIMULATED_FAIL)
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("SIMULATED_FAIL");
        } catch (Exception other) {
            throw new AssertionError("非预期异常", other);
        }
    }
}
