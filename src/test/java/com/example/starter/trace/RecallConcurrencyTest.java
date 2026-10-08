package com.example.starter.trace;

import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 召回并发边界测试：加工受理与召回创建竞态、同来源召回唯一、并发撤回版本控制。
 * 通过门闩协调真实并发，设置超时，断言最终结果与库内数据一致。
 */
class RecallConcurrencyTest extends BaseTraceTest {

    @Test
    void 加工与召回竞态_加工成功则被召回标记_否则被拦截() throws Exception {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-CC-RC", "10.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> processingFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    var response = processingBatchService.createProcessingBatch(
                            new CreateProcessingBatchRequest("REQ-CC-PB", "PB-CC-RC", List.of(batchId)));
                    return "OK:" + response.id();
                } catch (BizException e) {
                    return "BIZ:" + e.status().value();
                }
            });
            Future<RecallResponse> recallFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return recallService.createRecall(new CreateRecallRequest(
                        "REQ-CC-RC", 1L, PERIOD, "合成召回原因", "CRED-CC", "QC-CREATE"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            String processingOutcome = processingFuture.get(30, TimeUnit.SECONDS);
            RecallResponse recall = recallFuture.get(30, TimeUnit.SECONDS);
            assertThat(recall.status()).isEqualTo("ACTIVE");

            RecallResponse detail = recallService.getRecall(recall.id());
            assertThat(detail.harvestLinks())
                    .as("采收批必须被查入影响范围")
                    .singleElement()
                    .satisfies(l -> {
                        assertThat(l.harvestBatchId()).isEqualTo(batchId);
                        assertThat(l.status()).isEqualTo("ACTIVE");
                    });
            if (processingOutcome.startsWith("OK:")) {
                long processingId = Long.parseLong(processingOutcome.substring(3));
                assertThat(detail.processingLinks())
                        .as("竞态中已成功的加工必须被召回标记，不能漏标旧加工")
                        .singleElement()
                        .satisfies(l -> {
                            assertThat(l.processingBatchId()).isEqualTo(processingId);
                            assertThat(l.origin()).isEqualTo("INITIAL");
                        });
            } else {
                assertThat(processingOutcome)
                        .as("竞态中加工被活动召回拦截，不能漏拦新引用")
                        .isEqualTo("BIZ:409");
                assertThat(detail.processingLinks()).isEmpty();
            }

            // 无论竞态结果如何，此后的新引用都必须被拦截
            try {
                processingBatchService.createProcessingBatch(
                        new CreateProcessingBatchRequest("REQ-CC-PB2", "PB-CC-RC2", List.of(batchId)));
                org.assertj.core.api.Assertions.fail("活动召回期间不得新增加工引用");
            } catch (BizException e) {
                assertThat(e.status().value()).isEqualTo(409);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 并发创建同来源召回_仅一条生效() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        try {
                            recallService.createRecall(new CreateRecallRequest(
                                    "REQ-CC-DUP-" + i, 1L, PERIOD, "合成召回原因",
                                    "CRED-DUP-" + i, "QC-CREATE"));
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
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "BIZ:409");
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 并发撤回同一召回_仅一次成功() throws Exception {
        RecallResponse recall = recallService.createRecall(new CreateRecallRequest(
                "REQ-CC-WD-0", 1L, PERIOD, "合成召回原因", "CRED-WD", "QC-CREATE"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        try {
                            recallService.withdrawRecall(recall.id(), new WithdrawRecallRequest(
                                    "REQ-CC-WD-" + (i + 1), 1L, "QC-A", "QC-B",
                                    "WCRED-A-" + i, "WCRED-B-" + i));
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
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "BIZ:409");

            RecallResponse detail = recallService.getRecall(recall.id());
            assertThat(detail.status()).isEqualTo("WITHDRAWN");
            assertThat(detail.version()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
