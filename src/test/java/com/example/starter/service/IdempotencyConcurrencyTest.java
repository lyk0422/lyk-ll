package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.web.dto.AssessmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 幂等并发测试：同 requestId 同参数并发写，全部返回首次结果且仅落库一次。
 */
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private AssessmentService assessmentService;

    @Test
    void concurrentSameRequestIdSameParamsReturnFirstResult() throws Exception {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest req = new AssessmentRequest("req-conc", 1L, 1L, 3, List.of(800));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssessmentRequest.Result>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return assessmentService.assess(req);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<AssessmentRequest.Result> results = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(results).hasSize(threads);
        // 所有并发请求得到相同结果（首次结果），评估/资格/幂等记录均仅一条
        assertThat(results.stream().distinct().count()).isEqualTo(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(count("assessment")).isEqualTo(1);
        assertThat(count("qualification")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void concurrentVehicleConflictAllowsOnlyOneRunningRoute() throws Exception {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        insertRoute(2, null, "APPLIED");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long routeId = i + 1L;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    assessmentService.assess(
                            new AssessmentRequest("req-veh-" + routeId, routeId, 1L, 3, List.of(800)));
                    return "OK";
                } catch (com.example.starter.support.ConflictException e) {
                    return "CONFLICT";
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> outcomes = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        // 同一车辆并发评估两条线路：恰一条投入运行，另一条 409 冲突
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM route WHERE status = 'RUNNING'", Integer.class);
        assertThat(running).isEqualTo(1);
    }
}
