package com.example.starter.trial.domain;

import com.example.starter.trial.AbstractTrialIntegrationTest;
import com.example.starter.trial.api.dto.BlindingResponse;
import com.example.starter.trial.api.dto.EnrollRequest;
import com.example.starter.trial.api.dto.UnblindRequest;
import com.example.starter.trial.api.dto.UnblindResponse;
import com.example.starter.trial.api.dto.VisitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应急揭盲 H2 事务/并发测试与盲态查询测试。
 */
class UnblindingServiceIntegrationTest extends AbstractTrialIntegrationTest {

    private static final Instant CONSENT = Instant.parse("2026-04-10T06:00:00Z");

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private UnblindingService unblindingService;

    @Autowired
    private BlindingQueryService blindingQueryService;

    @Autowired
    private VisitService visitService;

    private void enroll(String screeningNo) {
        enrollmentService.enroll(new EnrollRequest(
                "ENR-" + screeningNo, screeningNo, DataSeeder.CENTER_SHANGHAI, CONSENT));
    }

    @Test
    void twoDistinctAuthorizedConfirmersInOneTransactionTakeEffectAndWriteOrderedAudit() {
        enroll("S-UB1");
        BlindingResponse before = blindingQueryService.getBlinding("S-UB1");
        assertNull(before.groupCode(), "揭盲前真实组别必须对查询方保密");
        assertEquals(TrialConstants.STATUS_ENROLLED, before.status());

        UnblindResponse response = unblindingService.unblind("S-UB1",
                new UnblindRequest("UB-1", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));

        assertEquals(TrialConstants.STATUS_UNBLINDED, response.status());
        assertNotNull(response.effectiveAtUtc());
        assertTrue(response.groupCode().equals(TrialConstants.GROUP_TREATMENT)
                || response.groupCode().equals(TrialConstants.GROUP_CONTROL));

        BlindingResponse after = blindingQueryService.getBlinding("S-UB1");
        assertEquals(response.groupCode(), after.groupCode(), "揭盲后查询返回真实组别");
        assertEquals(TrialConstants.STATUS_UNBLINDED, after.status());

        List<String> auditTypes = jdbcTemplate.queryForList(
                "SELECT event_type FROM unblind_audit WHERE screening_no = ? ORDER BY id",
                String.class, "S-UB1");
        assertEquals(List.of(
                TrialConstants.EVENT_FIRST_CONFIRMED,
                TrialConstants.EVENT_SECOND_CONFIRMED,
                TrialConstants.EVENT_EFFECTIVE), auditTypes);
    }

    @Test
    void sameConfirmerTwiceIsRejectedAndLeavesNoEffectAndNoAudit() {
        enroll("S-UB2");
        assertThrows(BusinessRuleException.class, () -> unblindingService.unblind("S-UB2",
                new UnblindRequest("UB-2", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_LI)));

        assertEquals(TrialConstants.STATUS_ENROLLED, blindingQueryService.getBlinding("S-UB2").status());
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_event WHERE screening_no = 'S-UB2'", Integer.class);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_audit WHERE screening_no = 'S-UB2'", Integer.class);
        assertEquals(0, eventCount, "单人确认不生效，不产生揭盲事件");
        assertEquals(0, auditCount, "失败事务回滚，不残留任何确认审计");
    }

    @Test
    void unauthorizedConfirmerIsRejectedWithNoEffect() {
        enroll("S-UB3");
        assertThrows(BusinessRuleException.class, () -> unblindingService.unblind("S-UB3",
                new UnblindRequest("UB-3", "DR-LI", "DR-HACKER")));
        assertThrows(BusinessRuleException.class, () -> unblindingService.unblind("S-UB3",
                new UnblindRequest("UB-4", "DR-HACKER", DataSeeder.UNBLINDER_DR_WANG)));
        assertEquals(TrialConstants.STATUS_ENROLLED, blindingQueryService.getBlinding("S-UB3").status());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_event WHERE screening_no = 'S-UB3'", Integer.class));
    }

    @Test
    void unblindingIsTerminalAndReplaysReturnFirstResultWithoutNewEvents() {
        enroll("S-UB4");
        UnblindResponse first = unblindingService.unblind("S-UB4",
                new UnblindRequest("UB-5", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));

        // 同 requestId 重放：返回首次结果。
        UnblindResponse replayByRequest = unblindingService.unblind("S-UB4",
                new UnblindRequest("UB-5", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));
        assertEquals(first.groupCode(), replayByRequest.groupCode());
        assertEquals(first.effectiveAtUtc(), replayByRequest.effectiveAtUtc());
        assertEquals(first.firstConfirmer(), replayByRequest.firstConfirmer());
        assertEquals(first.secondConfirmer(), replayByRequest.secondConfirmer());

        // 新 requestId 对已揭盲受试者再次请求：仍返回首次结果，不新增事件与审计。
        UnblindResponse replayByNewRequest = unblindingService.unblind("S-UB4",
                new UnblindRequest("UB-OTHER", DataSeeder.UNBLINDER_DR_WANG, DataSeeder.UNBLINDER_DR_LI));
        assertEquals(first.requestId(), replayByNewRequest.requestId());
        assertEquals(first.firstConfirmer(), replayByNewRequest.firstConfirmer());
        assertEquals(first.effectiveAtUtc(), replayByNewRequest.effectiveAtUtc());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_event WHERE screening_no = 'S-UB4'", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_audit WHERE screening_no = 'S-UB4'", Integer.class),
                "重复揭盲不得新增审计事件");
    }

    @Test
    void subsequentVisitsAreExcludedByUtcDayBoundaryAndFlagIsIrreversible() {
        // 固定揭盲生效时刻：UTC 2026-04-15 23:30，验证按 UTC 日界判定。
        timeProviderSet(Instant.parse("2026-04-15T23:30:00Z"));
        enroll("S-UB5");
        unblindingService.unblind("S-UB5",
                new UnblindRequest("UB-6", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));

        // 揭盲 UTC 日之前一日的访视：保留在主要分析集。
        var beforeVisit = visitService.recordVisit("S-UB5",
                new VisitRequest("V1", Instant.parse("2026-04-14T22:00:00Z")));
        // 同一 UTC 日内揭盲时刻之后的访视：按 UTC 日界属于揭盲日，排除。
        var sameDayLaterVisit = visitService.recordVisit("S-UB5",
                new VisitRequest("V2", Instant.parse("2026-04-15T23:45:00Z")));
        // 次日访视：排除。
        var nextDayVisit = visitService.recordVisit("S-UB5",
                new VisitRequest("V3", Instant.parse("2026-04-16T00:30:00Z")));

        assertFalse(beforeVisit.excludedFromMainAnalysis());
        assertTrue(sameDayLaterVisit.excludedFromMainAnalysis());
        assertTrue(nextDayVisit.excludedFromMainAnalysis());

        // 揭盲事务内登记的“后续访视”同样被批量排除（含揭盲后登记前已存在的当日/之后访视）。
        Boolean v2Flag = jdbcTemplate.queryForObject(
                "SELECT excluded_from_main_analysis FROM subject_visit WHERE visit_code = 'V2'",
                Boolean.class);
        assertEquals(Boolean.TRUE, v2Flag);
    }

    @Test
    void visitsRecordedBeforeUnblindingOnOrAfterUnblindUtcDayAreBulkExcludedWhenUnblindTakesEffect() {
        timeProviderSet(Instant.parse("2026-05-01T10:00:00Z"));
        enroll("S-UB6");
        // 揭盲前先登记两条访视（记录时未揭盲，均未排除）。
        visitService.recordVisit("S-UB6", new VisitRequest("BASE", Instant.parse("2026-04-30T12:00:00Z")));
        visitService.recordVisit("S-UB6", new VisitRequest("SAME-DAY", Instant.parse("2026-05-01T09:00:00Z")));
        unblindingService.unblind("S-UB6",
                new UnblindRequest("UB-7", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));

        var flags = jdbcTemplate.query(
                "SELECT visit_code, excluded_from_main_analysis FROM subject_visit WHERE screening_no = 'S-UB6'",
                rs -> {
                    java.util.Map<String, Boolean> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("visit_code"), rs.getBoolean("excluded_from_main_analysis"));
                    }
                    return result;
                });
        assertEquals(Boolean.FALSE, flags.get("BASE"), "揭盲 UTC 日之前访视不排除");
        assertEquals(Boolean.TRUE, flags.get("SAME-DAY"), "揭盲 UTC 日当日记账访视随生效批量排除");
    }

    @Test
    void concurrentUnblindRequestsForSameSubjectTakeEffectExactlyOnce() throws Exception {
        enroll("S-UB7");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UnblindResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                // 串行化后落败请求按终态重放语义返回首次结果（均不新增事件）。
                return unblindingService.unblind("S-UB7", new UnblindRequest(
                        "UBC-" + index, DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG));
            }));
        }
        start.countDown();
        List<UnblindResponse> responses = new ArrayList<>();
        for (Future<UnblindResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long distinctEffectTimes = responses.stream()
                .map(UnblindResponse::effectiveAtUtc).distinct().count();
        long distinctFirstRequestIds = responses.stream()
                .map(UnblindResponse::requestId).distinct().count();
        assertEquals(1, distinctEffectTimes, "所有并发结果必须指向同一首次生效事件");
        assertEquals(1, distinctFirstRequestIds, "所有并发结果必须返回首次 requestId");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_event WHERE screening_no = 'S-UB7'", Integer.class));
        // 只有生效的那笔保留 3 条审计；落败请求不残留任何确认审计。
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM unblind_audit WHERE screening_no = 'S-UB7'", Integer.class));
    }

    @Test
    void unblindUnknownSubjectReturnsNotFound() {
        assertThrows(NotFoundException.class, () -> unblindingService.unblind("GHOST",
                new UnblindRequest("UB-X", DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG)));
        assertThrows(NotFoundException.class, () -> blindingQueryService.getBlinding("GHOST"));
    }

    private void timeProviderSet(Instant fixedInstant) {
        timeProvider.setClock(Clock.fixed(fixedInstant, ZoneOffset.UTC));
    }
}
