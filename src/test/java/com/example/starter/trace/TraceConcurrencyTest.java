package com.example.starter.trace;

import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.dto.RapidTestRequest;
import com.example.starter.trace.dto.TestSubmissionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：同批同时刻仅一条检测链；同 requestId 并发请求仅生效一次。
 * 通过门闩协调真实并发，设置超时，断言最终结果与库内数据。
 */
class TraceConcurrencyTest extends BaseTraceTest {

    @Test
    void 并发快检同一采收批_仅一条检测链生效() throws Exception {
        long batchId = newBatch(1L);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        try {
                            TestSubmissionResponse r = residueTestService.submitRapidTest(batchId,
                                    new RapidTestRequest("REQ-CC-R" + i, "SMP-CC-" + i,
                                            new BigDecimal("60.00")));
                            return "OK:" + r.conclusion();
                        } catch (BizException e) {
                            return "BIZ:" + e.status().value();
                        }
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = new java.util.ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter(o -> o.equals("OK:SUSPECT")).count())
                    .as("恰好一条快检链生效").isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> o.equals("BIZ:409")).count())
                    .as("其余请求因检测链冲突被拒").isEqualTo(threads - 1);

            HarvestBatchResponse detail = harvestBatchService.getBatch(batchId);
            assertThat(detail.status()).isEqualTo("FROZEN");
            assertThat(detail.tests()).hasSize(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 并发同requestId同参_仅落库一次且返回首次结果() throws Exception {
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CreateHarvestBatchRequest request = new CreateHarvestBatchRequest(
                "REQ-CC-IDEM", "HB-CC-IDEM", PERIOD,
                List.of(new CreateHarvestBatchRequest.ContributionItem(1L, new BigDecimal("100.00"))));
        try {
            List<Future<HarvestBatchResponse>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return harvestBatchService.createBatch(request);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<HarvestBatchResponse> results = new java.util.ArrayList<>();
            for (Future<HarvestBatchResponse> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).allMatch(r -> r.equals(results.get(0)),
                    "并发同键同参应全部返回首次结果");

            HarvestBatchResponse detail = harvestBatchService.getBatch(results.get(0).id());
            assertThat(detail.batchNo()).isEqualTo("HB-CC-IDEM");
            assertThat(detail.contributions()).hasSize(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 并发同requestId异参_一次成功一次409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = List.of("HB-CC-X1", "HB-CC-X2").stream()
                    .map(batchNo -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        try {
                            harvestBatchService.createBatch(new CreateHarvestBatchRequest(
                                    "REQ-CC-CONFLICT", batchNo, PERIOD,
                                    List.of(new CreateHarvestBatchRequest.ContributionItem(
                                            1L, new BigDecimal("100.00")))));
                            return "OK";
                        } catch (BizException e) {
                            return "BIZ:" + e.status().value();
                        }
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = new java.util.ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "BIZ:" + HttpStatus.CONFLICT.value());
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
