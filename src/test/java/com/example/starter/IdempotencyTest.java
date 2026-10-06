package com.example.starter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.error.IdempotencyConflictException;
import com.example.starter.service.DefectService;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等语义：同键同参返回首次结果，同键异参 409，并发下同键仅生效一次。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    private RegisterDefectRequest request(String requestId, String position) {
        return new RegisterDefectRequest(requestId, null, bladeId("BLADE-A01"), position,
                "CRACK", new BigDecimal("100.00"), new BigDecimal("0.05"));
    }

    @Test
    void 同键同参返回首次结果_不重复落库() {
        RegisterDefectRequest req = request("req-idem-1", "叶中10-15m");

        DefectResponse first = defectService.registerDefect(req);
        DefectResponse replay = defectService.registerDefect(req);

        assertThat(replay).isEqualTo(first);
        assertThat(count("defect")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void 同键异参抛幂等冲突() {
        defectService.registerDefect(request("req-idem-2", "叶中10-15m"));

        assertThatThrownBy(() -> defectService.registerDefect(request("req-idem-2", "叶尖30-35m")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("defect")).isEqualTo(1);
    }

    @Test
    void 并发同键同参仅生效一次() throws Exception {
        RegisterDefectRequest req = request("req-idem-concurrent", "叶中10-15m");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<DefectResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return defectService.registerDefect(req);
            });
        }
        List<Future<DefectResponse>> futures = new ArrayList<>();
        for (Callable<DefectResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<DefectResponse> responses = new ArrayList<>();
        for (Future<DefectResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(responses).hasSize(threads);
        assertThat(responses.stream().map(DefectResponse::id).distinct()).hasSize(1);
        assertThat(count("defect")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }
}
