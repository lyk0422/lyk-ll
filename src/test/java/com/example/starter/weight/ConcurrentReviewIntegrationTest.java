package com.example.starter.weight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.DecisionRequest;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.ReviewRepository;
import com.example.starter.weight.service.EnforcementService;

/**
 * 并发集成测试：同一案件并发复检申请仅唯一受理（数据库唯一约束保证）。
 */
class ConcurrentReviewIntegrationTest extends BaseIntegrationTest {

    private static final String PLATE = "沪A10001";
    private static final String STATION = "ST001";

    @Autowired
    private EnforcementService service;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private CaseRepository caseRepository;

    @Test
    @DisplayName("同一案件并发复检申请：仅一个受理，其余收到 409 冲突")
    void concurrentReviewApplicationsOnlyOneAccepted() throws Exception {
        service.submitWeigh(new WeighRequest("W-CC", PLATE, STATION, WEIGH_TIME,
                WeighMode.DYNAMIC, new BigDecimal("40300.00")));
        service.issueDecision(new DecisionRequest("D-CC", PLATE, STATION, WEIGH_TIME));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReviewResponse>> futures = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    return service.applyReview(new ReviewApplyRequest(
                            "R-CC-" + idx, PLATE, STATION, WEIGH_TIME, "并发复检申请 " + idx));
                } catch (ApiException e) {
                    failures.add(e);
                    return null;
                }
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<ReviewResponse> accepted = new ArrayList<>();
        for (Future<ReviewResponse> future : futures) {
            ReviewResponse response = future.get(30, TimeUnit.SECONDS);
            if (response != null) {
                accepted.add(response);
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 仅一个受理成功，其余均为 409 唯一受理冲突
        assertThat(accepted).hasSize(1);
        assertThat(failures).hasSize(threads - 1);
        assertThat(failures).allSatisfy(t -> {
            ApiException ae = (ApiException) t;
            assertThat(ae.status()).isEqualTo(409);
            assertThat(ae.code()).isEqualTo("REVIEW_ALREADY_EXISTS");
        });

        // 数据库层面同一案件仅一条复检申请
        long caseId = caseRepository.findByBizKey(PLATE, STATION, WEIGH_TIME).id();
        assertThat(reviewRepository.findByCaseId(caseId)).isNotNull();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_request WHERE case_id = ?", Integer.class, caseId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("并发同键过磅上报：仅生成一条记录，其余命中去重或幂等重放")
    void concurrentWeighSameKeyOnlyOneRecord() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return service.submitWeigh(new WeighRequest(
                        "W-CC-" + idx, PLATE, STATION, WEIGH_TIME,
                        WeighMode.DYNAMIC, new BigDecimal("40300.00")));
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM weigh_record
                WHERE plate_no = ? AND station_code = ? AND weigh_time = ?
                """, Integer.class, PLATE, STATION, java.sql.Timestamp.from(WEIGH_TIME));
        assertThat(count).isEqualTo(1);
        Integer caseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enforcement_case WHERE plate_no = ?", Integer.class, PLATE);
        assertThat(caseCount).isEqualTo(1);
    }
}
