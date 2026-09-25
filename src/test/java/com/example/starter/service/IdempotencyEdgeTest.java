package com.example.starter.service;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Fixtures;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.ReadingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等边界：首次业务失败的结论同键同参原样重放（不重新执行、不改变状态）；
 * 同键同参并发只有一次真实业务执行。
 */
class IdempotencyEdgeTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private BackupPowerService service;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private Fixtures fixtures;

    @Test
    void first_business_failure_is_replayed_without_reexecution_even_after_state_becomes_valid() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));

        DispatchRequest request = new DispatchRequest("REQ-D1", "BS-1", T0, null);

        // 首次：无读数，422 业务失败。
        assertThatThrownBy(() -> idempotency.execute("REQ-D1", "DISPATCH", request,
                () -> service.handleDispatch(request)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("容量读数");

        // 之后补齐读数——若重新执行本可成功派单。
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("1000")));

        // 同键同参：重放首次 422，不重新执行，不产生调度单。
        assertThatThrownBy(() -> idempotency.execute("REQ-D1", "DISPATCH", request,
                () -> service.handleDispatch(request)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("容量读数");
        assertThat(count("dispatch_order")).isZero();
    }

    @Test
    void concurrent_same_request_and_params_execute_business_only_once() throws Exception {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("1000")));

        DispatchRequest request = new DispatchRequest("REQ-D1", "BS-1", T0, null);
        Callable<Object> task = () -> idempotency.execute("REQ-D1", "DISPATCH", request,
                () -> service.handleDispatch(request));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    })
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            IdempotencyService.Outcome first = (IdempotencyService.Outcome)
                    futures.get(0).get(15, TimeUnit.SECONDS);
            IdempotencyService.Outcome second = (IdempotencyService.Outcome)
                    futures.get(1).get(15, TimeUnit.SECONDS);

            long replays = java.util.stream.Stream.of(first, second)
                    .filter(IdempotencyService.Outcome::replayed).count();
            assertThat(replays).isEqualTo(1);

            // 首次结果为强类型 DTO，重放结果为首次响应快照（通用 Map），两者业务内容一致。
            IdempotencyService.Outcome primary = first.replayed() ? second : first;
            IdempotencyService.Outcome replay = first.replayed() ? first : second;
            DispatchResponse primaryResponse = (DispatchResponse) primary.body();
            assertThat(primaryResponse.created()).isTrue();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> replayedBody =
                    (java.util.Map<String, Object>) replay.body();
            assertThat(replayedBody.get("created")).isEqualTo(true);
            assertThat(String.valueOf(((java.util.Map<String, Object>) replayedBody.get("order")).get("orderId")))
                    .isEqualTo(String.valueOf(primaryResponse.order().orderId()));
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(count("generator_active_order")).isEqualTo(1);
    }
}
