package com.example.starter.weight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.weight.api.dto.OffsiteWeighRequest;
import com.example.starter.weight.api.dto.OffsiteWeighResponse;
import com.example.starter.weight.service.OffsiteEnforcementService;

/**
 * 非现场接入并发集成测试（真实 H2）：同键并发仅一条记录、窗口去重并发下只保留首次。
 */
class ConcurrentOffsiteIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE = "沪A10001";
    private static final String LANE = "ST001-L1";
    private static final String DEVICE = "DEV-ST001-L1";
    private static final String IMAGE = "IMG-20260901-0001";

    @Autowired
    private OffsiteEnforcementService offsiteService;

    private OffsiteWeighRequest offsite(String requestId) {
        return new OffsiteWeighRequest(requestId, PLATE, LANE, DEVICE, WEIGH_TIME,
                new BigDecimal("40300.00"), IMAGE);
    }

    @Test
    @DisplayName("并发同 requestId 同参接入：仅一条记录、一案一文，其余为幂等重放")
    void concurrentSameRequestIdOnlyOneRecord() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OffsiteWeighResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return offsiteService.ingest(offsite("OS-CC-SAME"));
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<OffsiteWeighResponse> responses = new ArrayList<>();
        for (Future<OffsiteWeighResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 全部成功返回且指向同一条记录、同一文书
        assertThat(responses).hasSize(threads);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.evidenceStatus()).isEqualTo("COMPLETE");
            assertThat(r.recordId()).isEqualTo(responses.get(0).recordId());
            assertThat(r.decisionDocNo()).isEqualTo(responses.get(0).decisionDocNo());
        });

        // 数据库层面：仅一条接入记录、一案、一条过磅记录、一条文书
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM offsite_weigh_record", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enforcement_case", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发同车牌同时刻不同 requestId：窗口去重只保留首次，其余判 DUPLICATE")
    void concurrentSameEventOnlyFirstRetained() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OffsiteWeighResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return offsiteService.ingest(offsite("OS-CC-" + idx));
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<OffsiteWeighResponse> responses = new ArrayList<>();
        for (Future<OffsiteWeighResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 恰一条 COMPLETE，其余全部 DUPLICATE
        assertThat(responses.stream().filter(r -> r.evidenceStatus().equals("COMPLETE")))
                .hasSize(1);
        assertThat(responses.stream().filter(r -> r.evidenceStatus().equals("DUPLICATE")))
                .hasSize(threads - 1);

        // 只保留首次：一案、一条过磅记录、一条文书；接入记录按请求各留一条审计
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enforcement_case", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_doc", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM offsite_weigh_record", Integer.class))
                .isEqualTo(threads);
    }
}
