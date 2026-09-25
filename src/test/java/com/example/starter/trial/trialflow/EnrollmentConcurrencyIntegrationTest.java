package com.example.starter.trial.trialflow;

import com.example.starter.trial.api.EnrollmentRequest;
import com.example.starter.trial.api.EnrollmentResponse;
import com.example.starter.trial.api.UnblindingConfirmRequest;
import com.example.starter.trial.api.UnblindingResponse;
import com.example.starter.trial.service.ConflictException;
import com.example.starter.trial.service.TrialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实多线程并发下的唯一入组、幂等重放、随机号不重号/区组比例与双人揭盲唯一生效测试。
 * 使用 CountDownLatch 同时释放线程，断言数据库最终状态；不依赖打印或固定休眠。
 */
class EnrollmentConcurrencyIntegrationTest extends AbstractTrialIntegrationTest {

    private static final String TRIAL = "CT-2026-CONC";
    private static final String SITE = "SITE-01";
    private static final Instant CONSENT = Instant.parse("2026-03-01T05:30:00Z");

    @Autowired
    private TrialService trialService;

    /** 并发任务结果容器：成功返回值或异常类型。 */
    private record Outcome(EnrollmentResponse response, String error) {
        boolean success() {
            return response != null;
        }
    }

    @Test
    void concurrentSameScreenNoOnlyOneEnrollmentSucceeds() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        EnrollmentResponse r = trialService.enroll(
                                new EnrollmentRequest("C-DUP-" + idx, TRIAL, "S-DUP", SITE, CONSENT));
                        return new Outcome(r, null);
                    } catch (ConflictException conflict) {
                        return new Outcome(null, "CONFLICT");
                    }
                }));
            }
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<Outcome> f : futures) {
                Outcome o = f.get(60, TimeUnit.SECONDS);
                if (o.success()) {
                    success++;
                } else if ("CONFLICT".equals(o.error())) {
                    conflict++;
                }
            }
            assertEquals(1, success, "并发同筛选号只允许成功一次");
            assertEquals(threads - 1, conflict, "其余并发入组必须冲突失败");
            assertEquals(1L, subjectCount("S-DUP"));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(1) FROM enrollment_request WHERE subject_screen_no = 'S-DUP'",
                    Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdAllReturnFirstAllocationWithoutExtraNumbers() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    EnrollmentResponse r = trialService.enroll(
                            new EnrollmentRequest("C-IDEM-SAME", TRIAL, "S-IDEM", SITE, CONSENT));
                    return new Outcome(r, null);
                }));
            }
            start.countDown();

            Set<Long> randomNumbers = new HashSet<>();
            int replayed = 0;
            for (Future<Outcome> f : futures) {
                Outcome o = f.get(60, TimeUnit.SECONDS);
                assertTrue(o.success(), "并发同 requestId 同参调用都应得到（首次或回放）结果");
                randomNumbers.add(o.response().randomizationNo());
                if (o.response().replayed()) {
                    replayed++;
                }
            }
            assertEquals(Set.of(1L), randomNumbers, "所有并发调用必须返回同一个随机号");
            assertEquals(threads - 1, replayed, "仅一例首次成功，其余为重放");
            assertEquals(1L, subjectCount("S-IDEM"));
            assertEquals(1L, currentSequenceNo());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDistinctSubjectsGetUniqueContiguousNumbersAndKeepBlockRatio() throws Exception {
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<EnrollmentResponse>> futures = new ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return trialService.enroll(new EnrollmentRequest(
                            "C-DISTINCT-" + idx, TRIAL, "S-D-" + idx, SITE, CONSENT));
                }));
            }
            start.countDown();

            Set<Long> numbers = new HashSet<>();
            List<EnrollmentResponse> responses = new ArrayList<>();
            for (Future<EnrollmentResponse> f : futures) {
                EnrollmentResponse r = f.get(60, TimeUnit.SECONDS);
                responses.add(r);
                assertTrue(numbers.add(r.randomizationNo()), "随机号不得重复: " + r.randomizationNo());
            }
            // 号码必须恰为 1..12，连续无缺号（无烧号），与最终序列一致
            assertEquals(12, numbers.size());
            for (long n = 1; n <= 12; n++) {
                assertTrue(numbers.contains(n), "缺少随机号 " + n);
            }
            assertEquals(12L, currentSequenceNo());

            // 按随机号（即区组位置）排序后，每区组严格 2A2B，且无连续 4 例同组
            List<String> byPosition = new ArrayList<>(Collections.nCopies(12, null));
            for (EnrollmentResponse r : responses) {
                byPosition.set((int) (r.randomizationNo() - 1), r.blindedGroupCode());
            }
            assertFalse(byPosition.contains(null));
            for (int blockStart = 0; blockStart < 12; blockStart += 4) {
                long a = byPosition.subList(blockStart, blockStart + 4).stream()
                        .filter("A"::equals).count();
                assertEquals(2, a, "并发入组后区组 " + blockStart + " 必须仍为 2A2B");
            }
            for (int i = 0; i + 4 <= byPosition.size(); i++) {
                assertEquals(2, new HashSet<>(byPosition.subList(i, i + 4)).size(),
                        "并发下出现连续 4 例同组，位置 " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSecondConfirmationsCompleteExactlyOnceWithSingleAuditTrail() throws Exception {
        trialService.enroll(new EnrollmentRequest("C-UB-E", TRIAL, "S-UB", SITE, CONSENT));
        trialService.confirmUnblinding(new UnblindingConfirmRequest("C-UB", TRIAL, "S-UB", "INV-001"));

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<UnblindingResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return trialService.confirmUnblinding(
                            new UnblindingConfirmRequest("C-UB", TRIAL, "S-UB", "INV-002"));
                }));
            }
            start.countDown();

            AtomicInteger firstCompletions = new AtomicInteger();
            AtomicInteger replays = new AtomicInteger();
            for (Future<UnblindingResponse> f : futures) {
                UnblindingResponse r = f.get(60, TimeUnit.SECONDS);
                assertEquals("COMPLETED", r.status());
                assertEquals("INV-001", r.confirmerA());
                assertEquals("INV-002", r.confirmerB());
                if (r.replayed()) {
                    replays.incrementAndGet();
                } else {
                    firstCompletions.incrementAndGet();
                }
            }
            assertEquals(1, firstCompletions.get(), "并发第二步确认只能有一次生效");
            assertEquals(threads - 1, replays.get());

            // 终态：受试者已揭盲，审计只新增 SECOND_CONFIRMED + UNBLINDED 两条，重复确认不新增事件
            assertEquals("UNBLINDED", trialService.getBlindedSubject(TRIAL, "S-UB").status());
            assertEquals(3, trialService.listAuditEvents(TRIAL, "S-UB").size());
        } finally {
            pool.shutdownNow();
        }
    }

    private long subjectCount(String screenNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(1) FROM trial_subject WHERE trial_id = ? AND subject_screen_no = ?",
                Long.class, TRIAL, screenNo);
    }

    private long currentSequenceNo() {
        return jdbc.queryForObject(
                "SELECT current_no FROM site_random_sequence WHERE trial_id = ? AND site_code = ?",
                Long.class, TRIAL, SITE);
    }
}
