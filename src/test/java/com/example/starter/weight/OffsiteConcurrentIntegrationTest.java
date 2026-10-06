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
import com.example.starter.weight.repo.OffsiteWeighRepository;
import com.example.starter.weight.service.OffsiteEnforcementService;

/**
 * 非现场接入并发集成测试（真实 H2）：同 requestId 并发幂等唯一、
 * 同车牌窗口内并发去重只保留首次（车辆行锁串行化保证）。
 */
class OffsiteConcurrentIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE = "沪A10001";
    private static final String LANE = "L01";
    private static final String DEVICE = "DEV-L01";
    private static final String IMAGE = "IMG-SYN-0001";

    @Autowired
    private OffsiteEnforcementService offsiteService;
    @Autowired
    private OffsiteWeighRepository offsiteWeighRepository;

    private OffsiteWeighRequest offsite(String requestId, java.time.Instant time) {
        return new OffsiteWeighRequest(requestId, PLATE, LANE, time,
                new BigDecimal("40300.00"), IMAGE, DEVICE);
    }

    @Test
    @DisplayName("并发同 requestId 同参接入：仅一条记录一个案件一份文书，其余幂等重放")
    void concurrentSameRequestIdOnlyOneRecord() throws Exception {
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OffsiteWeighResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return offsiteService.ingest(offsite("OS-CC-SAME", WEIGH_TIME));
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

        // 全部成功且指向同一条首次记录
        assertThat(responses).hasSize(threads);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("AUTO_DECIDED");
            assertThat(r.recordId()).isEqualTo(responses.get(0).recordId());
        });
        assertThat(responses.stream().filter(OffsiteWeighResponse::replayed).count())
                .isEqualTo(threads - 1L);

        assertThat(offsiteWeighRepository.countByPlate(PLATE)).isEqualTo(1);
        Integer caseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enforcement_case WHERE plate_no = ?", Integer.class, PLATE);
        assertThat(caseCount).isEqualTo(1);
        Integer docCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_doc", Integer.class);
        assertThat(docCount).isEqualTo(1);
        Integer pointsSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM points_ledger", Integer.class);
        assertThat(pointsSum).isEqualTo(3);
    }

    @Test
    @DisplayName("并发同车牌窗口内接入（不同 requestId）：窗口内只保留首次，其余去重")
    void concurrentSamePlateWithinWindowKeepsFirstOnly() throws Exception {
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OffsiteWeighResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                // 同一车牌、窗口内不同时刻（0~4 秒）、不同 requestId
                return offsiteService.ingest(offsite("OS-CC-WIN-" + idx, WEIGH_TIME.plusSeconds(idx)));
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

        // 窗口内只保留首次：恰好一条接入记录、一个案件、一份自动文书
        assertThat(responses.stream().filter(r -> !r.deduped()).count()).isEqualTo(1L);
        assertThat(responses.stream().filter(OffsiteWeighResponse::deduped).count())
                .isEqualTo(threads - 1L);
        assertThat(offsiteWeighRepository.countByPlate(PLATE)).isEqualTo(1);
        Integer caseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enforcement_case WHERE plate_no = ?", Integer.class, PLATE);
        assertThat(caseCount).isEqualTo(1);
        Integer docCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_doc", Integer.class);
        assertThat(docCount).isEqualTo(1);
    }
}
