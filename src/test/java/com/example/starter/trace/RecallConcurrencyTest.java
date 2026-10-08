package com.example.starter.trace;

import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.service.RecallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源召回并发边界测试：加工受理与召回创建竞态不漏拦也不漏标；
 * 同来源并发创建仅一条 ACTIVE；同 requestId 并发仅生效一次。
 */
class RecallConcurrencyTest extends BaseTraceTest {

    @Autowired
    private RecallService recallService;

    @Test
    void 加工受理与召回创建竞态_加工成功则必被标记_否则必被拦截() throws Exception {
        long released = newBatch(1L);
        rapid(released, "SMP-RC-CC1", "10.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> processingFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ProcessingBatchResponse r = processingBatchService.createProcessingBatch(
                            new CreateProcessingBatchRequest("REQ-RC-CC1-PB", "PB-RC-CC1",
                                    List.of(released)));
                    return "OK:" + r.id();
                } catch (BizException e) {
                    return "BIZ:" + e.status().value();
                }
            });
            Future<RecallResponse> recallFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return recallService.createRecall(new CreateRecallRequest(
                        "REQ-RC-CC1-RC", 1L, PERIOD, "合成原因", "CRED-CC1", "QC-CC1"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            String processingOutcome = processingFuture.get(30, TimeUnit.SECONDS);
            RecallResponse recall = recallFuture.get(30, TimeUnit.SECONDS);

            assertThat(recall.initialFrozenLinks())
                    .extracting(RecallResponse.LinkView::harvestBatchId)
                    .contains(released);
            if (processingOutcome.startsWith("OK:")) {
                // 加工先受理成功：后来的召回必须把该加工批查入影响范围并加隔离标记
                long processingId = Long.parseLong(processingOutcome.substring(3));
                assertThat(recall.processingMarks())
                        .extracting(RecallResponse.ProcessingMarkView::processingBatchId)
                        .contains(processingId);
            } else {
                // 召回先生效：加工必须被拦截，不能漏拦新引用
                assertThat(processingOutcome).isEqualTo("BIZ:409");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 并发同来源召回创建_仅一条活动召回生效() throws Exception {
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
                                    "REQ-RC-CC2-" + i, 1L, PERIOD, "合成原因-" + i,
                                    "CRED-CC2-" + i, "QC-CC2-" + i));
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
    void 并发同requestId召回创建_仅落库一次且返回首次结果() throws Exception {
        long released = newBatch(1L);
        rapid(released, "SMP-RC-CC3", "10.00");
        CreateRecallRequest request = new CreateRecallRequest(
                "REQ-RC-CC3", 1L, PERIOD, "合成原因", "CRED-CC3", "QC-CC3");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RecallResponse>> futures = java.util.stream.IntStream.range(0, 3)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return recallService.createRecall(request);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RecallResponse> results = new java.util.ArrayList<>();
            for (Future<RecallResponse> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).allMatch(r -> r.equals(results.get(0)),
                    "并发同键同参应全部返回首次响应");

            RecallResponse detail = recallService.getRecall(results.get(0).id());
            assertThat(detail.status()).isEqualTo("ACTIVE");
            assertThat(detail.initialFrozenLinks())
                    .extracting(RecallResponse.LinkView::harvestBatchId)
                    .containsExactly(released);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
