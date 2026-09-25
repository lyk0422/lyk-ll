package com.example.starter.trial.domain;

import com.example.starter.trial.AbstractTrialIntegrationTest;
import com.example.starter.trial.api.dto.EnrollRequest;
import com.example.starter.trial.api.dto.EnrollResponse;
import com.example.starter.trial.support.TransactionRollbackFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机入组 H2 事务/并发测试。
 */
class EnrollmentServiceIntegrationTest extends AbstractTrialIntegrationTest {

    private static final Instant CONSENT = Instant.parse("2026-03-01T08:00:00Z");

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private TransactionRollbackFixture rollbackFixture;

    private EnrollRequest request(String requestId, String screeningNo, String center, Instant consent) {
        return new EnrollRequest(requestId, screeningNo, center, consent);
    }

    @Test
    void eachCompletedBlockKeepsTwoTreatmentTwoControlAcrossEightEnrollments() {
        String center = DataSeeder.CENTER_SHANGHAI;
        for (int i = 1; i <= 8; i++) {
            enrollmentService.enroll(request("REQ-" + i, "S-" + String.format("%03d", i), center,
                    CONSENT.plusSeconds(i)));
        }

        Map<Long, Map<String, Long>> groupsPerBlock = jdbcTemplate.queryForList(
                "SELECT block_no, group_code FROM subject_enrollment "
                        + "WHERE trial_code = ? AND center_code = ?",
                TrialConstants.DEFAULT_TRIAL, center).stream()
                .collect(Collectors.groupingBy(
                        row -> ((Number) row.get("block_no")).longValue(),
                        Collectors.groupingBy(
                                row -> (String) row.get("group_code"),
                                Collectors.counting())));

        assertEquals(2, groupsPerBlock.size());
        for (var groups : groupsPerBlock.values()) {
            assertEquals(2L, groups.get(TrialConstants.GROUP_TREATMENT), "每区组必须 2 例试验组");
            assertEquals(2L, groups.get(TrialConstants.GROUP_CONTROL), "每区组必须 2 例对照组");
        }

        List<String> orderedGroups = jdbcTemplate.queryForList(
                "SELECT group_code FROM subject_enrollment "
                        + "WHERE trial_code = ? AND center_code = ? ORDER BY random_no",
                String.class, TrialConstants.DEFAULT_TRIAL, center).stream()
                .map(g -> g.equals(TrialConstants.GROUP_TREATMENT) ? "T" : "C")
                .toList();
        String sequence = String.join("", orderedGroups);
        assertFalse(sequence.contains("TTTT"), sequence);
        assertFalse(sequence.contains("CCCC"), sequence);
    }

    @Test
    void randomNumbersAreSequentialAndNeverReusedAfterOuterRollback() {
        String center = DataSeeder.CENTER_BEIJING;
        EnrollResponse first = enrollmentService.enroll(request("R-1", "SUB-1", center, CONSENT));
        assertEquals(1L, first.randomNo());

        // 模拟另一次入组在发号后外层事务失败：随机号 2 已在独立事务提交。
        assertThrows(IllegalStateException.class,
                () -> rollbackFixture.issueThenRollback(TrialConstants.DEFAULT_TRIAL, center));

        EnrollResponse third = enrollmentService.enroll(request("R-2", "SUB-2", center, CONSENT));
        assertEquals(3L, third.randomNo(), "回滚作废的随机号 2 不得被重用");

        List<Long> issued = jdbcTemplate.queryForList(
                "SELECT random_no FROM subject_enrollment WHERE trial_code = ? AND center_code = ? ORDER BY random_no",
                Long.class, TrialConstants.DEFAULT_TRIAL, center);
        assertEquals(List.of(1L, 3L), issued);
        Long ledgerNext = jdbcTemplate.queryForObject(
                "SELECT next_random_no FROM random_number_ledger WHERE trial_code = ? AND center_code = ?",
                Long.class, TrialConstants.DEFAULT_TRIAL, center);
        assertEquals(4L, ledgerNext);
    }

    @Test
    void sameRequestWithSameParametersReturnsFirstAllocationWithoutNewRandomNumber() {
        EnrollResponse first = enrollmentService.enroll(request("IDEM-1", "SUB-X",
                DataSeeder.CENTER_SHANGHAI, CONSENT));
        EnrollResponse replay = enrollmentService.enroll(request("IDEM-1", "SUB-X",
                DataSeeder.CENTER_SHANGHAI, CONSENT));
        assertEquals(first.randomNo(), replay.randomNo());
        assertEquals(first.blindedCode(), replay.blindedCode());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_enrollment WHERE trial_code = ? AND request_id = ?",
                Integer.class, TrialConstants.DEFAULT_TRIAL, "IDEM-1");
        assertEquals(1, count);
    }

    @Test
    void sameRequestWithDifferentParametersReturnsConflictAndIssuesNoNumber() {
        enrollmentService.enroll(request("IDEM-2", "SUB-Y", DataSeeder.CENTER_SHANGHAI, CONSENT));
        Long before = jdbcTemplate.queryForObject(
                "SELECT next_random_no FROM random_number_ledger WHERE trial_code = ? AND center_code = ?",
                Long.class, TrialConstants.DEFAULT_TRIAL, DataSeeder.CENTER_SHANGHAI);

        // 同 requestId 但同意时刻不同 => 指纹不同 => 409。
        assertThrows(ConflictException.class, () -> enrollmentService.enroll(
                request("IDEM-2", "SUB-Y", DataSeeder.CENTER_SHANGHAI, CONSENT.plusSeconds(60))));

        Long after = jdbcTemplate.queryForObject(
                "SELECT next_random_no FROM random_number_ledger WHERE trial_code = ? AND center_code = ?",
                Long.class, TrialConstants.DEFAULT_TRIAL, DataSeeder.CENTER_SHANGHAI);
        assertEquals(before, after, "409 冲突不得发放新随机号");
    }

    @Test
    void duplicateScreeningNumberIsRejectedEvenWithDifferentRequestId() {
        enrollmentService.enroll(request("DUP-1", "SUB-Z", DataSeeder.CENTER_SHANGHAI, CONSENT));
        assertThrows(ConflictException.class, () -> enrollmentService.enroll(
                request("DUP-2", "SUB-Z", DataSeeder.CENTER_SHANGHAI, CONSENT.plusSeconds(5))));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_enrollment WHERE screening_no = 'SUB-Z'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void unknownCenterIsRejectedBeforeIssuingNumber() {
        assertThrows(BusinessRuleException.class, () -> enrollmentService.enroll(
                request("X-1", "SUB-Q", "C-UNKNOWN", CONSENT)));
    }

    @Test
    void concurrentEnrollmentsOfSameScreeningNumberSucceedExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<Long> successRandomNos = java.util.Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    EnrollResponse response = enrollmentService.enroll(request(
                            "CONC-" + index, "SUB-CONC", DataSeeder.CENTER_SHANGHAI,
                            CONSENT.plusSeconds(index)));
                    successRandomNos.add(response.randomNo());
                } catch (ConflictException ex) {
                    errors.add(ex.getMessage());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successRandomNos.size(), "同筛选号并发入组只能成功一次");
        assertEquals(threads - 1, errors.size(), "其余并发请求必须冲突失败");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_enrollment WHERE screening_no = 'SUB-CONC'",
                Integer.class);
        assertEquals(1, rows);
        // 落败请求在中心行锁内复查即被拦截，尚未发号，故号段只前进 1（下一号为 2）。
        Long ledgerNext = jdbcTemplate.queryForObject(
                "SELECT next_random_no FROM random_number_ledger WHERE trial_code = ? AND center_code = ?",
                Long.class, TrialConstants.DEFAULT_TRIAL, DataSeeder.CENTER_SHANGHAI);
        assertEquals(2L, ledgerNext, "冲突失败的并发请求不得消耗随机号");
    }

    @Test
    void concurrentEnrollmentsOfDistinctSubjectsGetUniqueSequentialNumbers() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return enrollmentService.enroll(request("PARA-" + index, "SUB-P" + index,
                        DataSeeder.CENTER_SHANGHAI, CONSENT.plusSeconds(index))).randomNo();
            }));
        }
        start.countDown();
        Set<Long> numbers = new HashSet<>();
        for (Future<Long> future : futures) {
            numbers.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(threads, numbers.size(), "并發入组随机号不得重复");
        long max = numbers.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertEquals(threads, max, "同中心随机号从 1 连续递增发放");
    }

    @Test
    void enrollmentIsStratifiedIndependentlyByCenter() {
        EnrollResponse sh = enrollmentService.enroll(request("STR-1", "SUB-SH",
                DataSeeder.CENTER_SHANGHAI, CONSENT));
        EnrollResponse bj = enrollmentService.enroll(request("STR-2", "SUB-BJ",
                DataSeeder.CENTER_BEIJING, CONSENT));
        assertEquals(1L, sh.randomNo());
        assertEquals(1L, bj.randomNo(), "随机号按中心内独立递增");
        assertNotEquals(sh.centerCode(), bj.centerCode());
    }
}
