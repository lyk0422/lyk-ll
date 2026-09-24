package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.common.ApiException;
import com.example.starter.enrollment.EnrollmentRepository;
import com.example.starter.enrollment.EnrollmentRequest;
import com.example.starter.enrollment.EnrollmentResponse;
import com.example.starter.enrollment.EnrollmentService;

/**
 * 入组主流程与边界测试：区组比例约束、随机号不重发、同键重放、同键异参 409、并发唯一入组。
 * 使用真实 H2（MySQL 兼容模式）验证唯一约束与事务回滚，不用 mock 替代。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class EnrollmentFlowTest {

    private static final String TRIAL = "TRIAL-001";
    private static final Instant CONSENT = Instant.parse("2025-12-01T08:30:00Z");

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private EnrollmentRepository enrollmentRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanData() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM visit_record");
        jdbc.update("DELETE FROM unblinding_record");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM randomization_block");
        jdbc.update("DELETE FROM random_counter");
    }

    @Test
    void blockRatioAndNoFourConsecutiveSameArm() {
        List<Character> arms = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            EnrollmentResponse response = enrollmentService.enroll(TRIAL,
                    new EnrollmentRequest("REQ-B" + i, "SCR-B" + i, "C01", CONSENT));
            assertThat(response.randomNo()).isEqualTo(i);
            arms.add(actualArm("SCR-B" + i));
        }
        // 每个区组（4 例）保持 2:2 设计比例
        for (int block = 0; block < 3; block++) {
            List<Character> window = arms.subList(block * 4, block * 4 + 4);
            assertThat(window.stream().filter(a -> a == 'T').count()).isEqualTo(2);
            assertThat(window.stream().filter(a -> a == 'C').count()).isEqualTo(2);
        }
        // 不允许连续 4 例同组（含跨区组边界）
        for (int i = 0; i + 3 < arms.size(); i++) {
            List<Character> run = arms.subList(i, i + 4);
            assertThat(run.stream().distinct().count()).isGreaterThan(1);
        }
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        EnrollmentRequest request = new EnrollmentRequest("REQ-1", "SCR-1", "C01", CONSENT);
        EnrollmentResponse first = enrollmentService.enroll(TRIAL, request);
        EnrollmentResponse replay = enrollmentService.enroll(TRIAL, request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.randomNo()).isEqualTo(first.randomNo());
        assertThat(replay.armCode()).isEqualTo(first.armCode());
        assertThat(countRows("enrollment")).isEqualTo(1);
        assertThat(countRows("audit_event")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndIssuesNoRandomNumber() {
        enrollmentService.enroll(TRIAL, new EnrollmentRequest("REQ-2", "SCR-2", "C01", CONSENT));

        EnrollmentRequest tampered = new EnrollmentRequest("REQ-2", "SCR-2", "C01",
                Instant.parse("2025-12-02T08:30:00Z"));
        assertThatThrownBy(() -> enrollmentService.enroll(TRIAL, tampered))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("REQUEST_ID_CONFLICT");
                });
        // 冲突请求不得发放新随机号：下一位受试者仍拿到 2 号
        EnrollmentResponse next = enrollmentService.enroll(TRIAL,
                new EnrollmentRequest("REQ-3", "SCR-3", "C01", CONSENT));
        assertThat(next.randomNo()).isEqualTo(2);
    }

    @Test
    void duplicateScreeningNumberReturns409() {
        enrollmentService.enroll(TRIAL, new EnrollmentRequest("REQ-4", "SCR-4", "C01", CONSENT));
        assertThatThrownBy(() -> enrollmentService.enroll(TRIAL,
                new EnrollmentRequest("REQ-5", "SCR-4", "C01", CONSENT)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("SUBJECT_ALREADY_ENROLLED");
                });
        assertThat(countRows("enrollment")).isEqualTo(1);
    }

    @Test
    void issuedRandomNumberIsNotReusedAfterRollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        // 外层事务回滚：入组记录与区组计划回滚，但已发出的随机号 1 不得重用
        outer.execute(status -> {
            enrollmentService.enroll(TRIAL, new EnrollmentRequest("REQ-R1", "SCR-R1", "C01", CONSENT));
            status.setRollbackOnly();
            return null;
        });

        long trialId = enrollmentRepository.findTrialByCode(TRIAL).id();
        assertThat(enrollmentRepository.findByScreening(trialId, "SCR-R1")).isNull();

        EnrollmentResponse second = enrollmentService.enroll(TRIAL,
                new EnrollmentRequest("REQ-R2", "SCR-R2", "C01", CONSENT));
        assertThat(second.randomNo()).isEqualTo(2);
    }

    @Test
    void concurrentEnrollmentWithSameScreeningNumberSucceedsOnlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return enrollmentService.enroll(TRIAL,
                            new EnrollmentRequest("REQ-C" + index, "SCR-C1", "C01", CONSENT));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long successes = results.stream().filter(EnrollmentResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException e
                        && e.getStatus() == HttpStatus.CONFLICT
                        && "SUBJECT_ALREADY_ENROLLED".equals(e.getCode()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(countRows("enrollment")).isEqualTo(1);
    }

    @Test
    void concurrentReplayOfSameRequestReturnsSameRandomNumber() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        EnrollmentRequest request = new EnrollmentRequest("REQ-SAME", "SCR-S1", "C01", CONSENT);
        List<Future<EnrollmentResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return enrollmentService.enroll(TRIAL, request);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<EnrollmentResponse> responses = new ArrayList<>();
        for (Future<EnrollmentResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(responses).allMatch(r -> r.randomNo() == responses.get(0).randomNo());
        assertThat(responses.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(countRows("enrollment")).isEqualTo(1);
        assertThat(countRows("audit_event")).isEqualTo(1);
    }

    private char actualArm(String screeningNo) {
        long trialId = enrollmentRepository.findTrialByCode(TRIAL).id();
        return enrollmentRepository.findByScreening(trialId, screeningNo).actualArm();
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
