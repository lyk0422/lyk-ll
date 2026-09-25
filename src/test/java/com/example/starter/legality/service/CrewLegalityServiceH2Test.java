package com.example.starter.legality.service;

import com.example.starter.legality.api.BatchCheckRequest;
import com.example.starter.legality.api.BatchCheckResponse;
import com.example.starter.legality.api.DelayRequest;
import com.example.starter.legality.api.DelayResponse;
import com.example.starter.legality.api.FinalizeResponse;
import com.example.starter.legality.api.ReportRequest;
import com.example.starter.legality.api.ReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于嵌入式 H2（MODE=MySQL）的服务层事务/并发集成测试，不使用 mock 替代数据库边界。
 */
@SpringBootTest
class CrewLegalityServiceH2Test {

    @Autowired
    private CrewLegalityService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM delay_event");
        jdbcTemplate.update("DELETE FROM batch_member");
        jdbcTemplate.update("DELETE FROM check_batch");
        jdbcTemplate.update("DELETE FROM duty_record");
    }

    private static OffsetDateTime utc(String text) {
        return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private ReportRequest report(String requestId, String crew, String flight,
                                 String report, String end) {
        return new ReportRequest(requestId, null, crew, flight, utc(report), utc(end));
    }

    // ---- 报到主流程与幂等 ----

    @Test
    void reportLegalDutyAndReplayReturnsFirstVerdict() {
        ReportRequest request = report("REQ-1", "C1", "CA100",
                "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z");
        ReportResponse first = service.report(request);
        assertThat(first.legal()).isTrue();
        assertThat(first.reasons()).isEmpty();
        assertThat(first.replayed()).isFalse();

        ReportResponse replay = service.report(request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.legal()).isEqualTo(first.legal());
        assertThat(replay.delayMinutes()).isZero();
        assertThat(countDuties()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndWritesNothing() {
        service.report(report("REQ-2", "C1", "CA100",
                "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z"));

        ReportRequest changed = report("REQ-2", "C1", "CA100",
                "2026-03-10T07:00:00Z", "2026-03-10T19:00:00Z");
        assertThatThrownBy(() -> service.report(changed))
                .isInstanceOf(IdempotentConflictException.class);
        assertThat(countDuties()).isEqualTo(1);
    }

    @Test
    void endBeforeReportIsBadRequest() {
        assertThatThrownBy(() -> service.report(report("REQ-BAD", "C1", "CA100",
                "2026-03-10T19:00:00Z", "2026-03-10T06:00:00Z")))
                .isInstanceOf(BadRequestException.class);
        assertThat(countDuties()).isZero();
    }

    // ---- 三档 FDP 与休息期端到端 ----

    @Test
    void fdpTiersAndRestBoundaryEvaluatedAgainstH2History() {
        // 夜间档恰 10 小时合法。
        assertThat(service.report(report("REQ-N10", "C2", "CA200",
                "2026-03-10T22:00:00Z", "2026-03-11T08:00:00Z")).legal()).isTrue();
        // 超 1 分钟违规。
        ReportResponse over = service.report(report("REQ-N11", "C3", "CA201",
                "2026-03-10T22:00:00Z", "2026-03-11T08:01:00Z"));
        assertThat(over.legal()).isFalse();
        assertThat(over.reasons()).containsExactly("M_FDP_LIMIT");

        // 前一班 16:00 结束，次日 01:59 报到休息不足 10 小时。
        service.report(report("REQ-R1", "C4", "CA300",
                "2026-03-10T06:00:00Z", "2026-03-10T16:00:00Z"));
        ReportResponse shortRest = service.report(report("REQ-R2", "C4", "CA301",
                "2026-03-11T01:59:00Z", "2026-03-11T03:00:00Z"));
        assertThat(shortRest.legal()).isFalse();
        assertThat(shortRest.reasons()).containsExactly("M_REST_LIMIT");
        // 恰满 10 小时合法（换成员避免历史干扰）。
        service.report(report("REQ-R3", "C5", "CA302",
                "2026-03-10T06:00:00Z", "2026-03-10T16:00:00Z"));
        assertThat(service.report(report("REQ-R4", "C5", "CA303",
                "2026-03-11T02:00:00Z", "2026-03-11T03:00:00Z")).legal()).isTrue();
    }

    // ---- 7 日 60 小时端到端 ----

    @Test
    void rolling60HoursBoundaryAcrossPersistedDuties() {
        // D-6..D-1 每日 9 小时执勤（15 小时间隔，休息充分）。
        String[] days = {"2026-03-10", "2026-03-11", "2026-03-12", "2026-03-13",
                "2026-03-14", "2026-03-15"};
        int idx = 0;
        for (String day : days) {
            assertThat(service.report(report("REQ-60-" + idx++, "C6", "CA4" + idx,
                    day + "T06:00:00Z", day + "T15:00:00Z")).legal()).isTrue();
        }
        // 第 7 日再执勤 6 小时，窗口恰 60 小时合法。
        ReportResponse exactly60 = service.report(report("REQ-60-FIT", "C6", "CA499",
                "2026-03-16T06:00:00Z", "2026-03-16T12:00:00Z"));
        assertThat(exactly60.legal()).isTrue();

        // 另一成员：同样历史，第 7 日 6 小时 1 分 → 超 60 小时违规。
        for (int i = 0; i < days.length; i++) {
            service.report(report("REQ-60B-" + i, "C7", "CB4" + i,
                    days[i] + "T06:00:00Z", days[i] + "T15:00:00Z"));
        }
        ReportResponse over60 = service.report(report("REQ-60B-OVER", "C7", "CB499",
                "2026-03-16T06:00:00Z", "2026-03-16T12:01:00Z"));
        assertThat(over60.legal()).isFalse();
        assertThat(over60.reasons()).containsExactly("M_ROLLING_60H");
    }

    // ---- 整组同事务一致判定 ----

    @Test
    void batchCheckMarksGroupIllegalWhenAnyMemberViolatesAndPersistsConsistently() {
        BatchCheckRequest request = new BatchCheckRequest("B1", List.of(
                new BatchCheckRequest.MemberDuty("REQ-B1-A", "CA", "CA500",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T19:00:00Z")),
                // 夜间档 10 小时 1 分，违规
                new BatchCheckRequest.MemberDuty("REQ-B1-B", "CB", "CA501",
                        utc("2026-03-10T22:00:00Z"), utc("2026-03-11T08:01:00Z"))));

        BatchCheckResponse response = service.checkBatch(request);
        assertThat(response.legal()).isFalse();
        assertThat(response.status()).isEqualTo("CHECKED");
        assertThat(response.memberResults()).hasSize(2);
        assertThat(response.memberResults().stream().filter(BatchCheckResponse.MemberResult::legal))
                .singleElement().extracting(BatchCheckResponse.MemberResult::crewId).isEqualTo("CA");
        BatchCheckResponse.MemberResult violator = response.memberResults().stream()
                .filter(r -> !r.legal()).findFirst().orElseThrow();
        assertThat(violator.crewId()).isEqualTo("CB");
        assertThat(violator.reasons()).containsExactly("M_FDP_LIMIT");

        // 成员状态与整组结论在库中一致。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM check_batch WHERE batch_id = 'B1'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_member WHERE batch_id = 'B1' AND legal = 0",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record WHERE request_id = 'REQ-B1-B' AND legal = 0",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void batchCheckIsIdempotentAcrossReplaysAndSameCrewMultipleSegmentsAllowed() {
        BatchCheckRequest request = new BatchCheckRequest("B2", List.of(
                new BatchCheckRequest.MemberDuty("REQ-B2-A", "CA", "CA600",
                        utc("2026-03-10T13:00:00Z"), utc("2026-03-11T01:00:00Z"))));
        assertThat(service.checkBatch(request).legal()).isTrue();
        assertThat(service.checkBatch(request).legal()).isTrue();
        assertThat(countDuties()).isEqualTo(1);

        // 同一成员在同一批次可包含多段值勤（不同航班），重复校验幂等。
        BatchCheckRequest twoSegments = new BatchCheckRequest("B2", List.of(
                new BatchCheckRequest.MemberDuty("REQ-B2-A", "CA", "CA600",
                        utc("2026-03-10T13:00:00Z"), utc("2026-03-11T01:00:00Z")),
                new BatchCheckRequest.MemberDuty("REQ-B2-A2", "CA", "CA601",
                        utc("2026-03-11T13:00:00Z"), utc("2026-03-12T00:00:00Z"))));
        BatchCheckResponse response = service.checkBatch(twoSegments);
        assertThat(response.legal()).isTrue();
        assertThat(response.memberResults()).hasSize(1);
        assertThat(response.memberResults().get(0).crewId()).isEqualTo("CA");
        assertThat(countDuties()).isEqualTo(2);

        // 结束早于报到 → 400。
        BatchCheckRequest invalid = new BatchCheckRequest("B2", List.of(
                new BatchCheckRequest.MemberDuty("REQ-B2-BAD", "CZ", "CA602",
                        utc("2026-03-10T13:00:00Z"), utc("2026-03-10T12:00:00Z"))));
        assertThatThrownBy(() -> service.checkBatch(invalid)).isInstanceOf(BadRequestException.class);
    }

    // ---- 并发唯一受理 ----

    @Test
    void concurrentSameSlotSubmissionsAcceptExactlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReportResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.report(report("REQ-CONC-" + seq, "CC", "CA700",
                            "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z"));
                }));
            }
            start.countDown();
            for (Future<ReportResponse> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).legal()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        // 同一成员同一报到时刻（即使航班号不同）只落一条记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record WHERE crew_id = 'CC' "
                        + "AND report_time = TIMESTAMP '2026-03-10 06:00:00'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void sameCrewSameReportTimeDifferentFlightTreatedAsDuplicateSlot() {
        ReportResponse first = service.report(report("REQ-SLOT-1", "CK", "CA701",
                "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z"));
        assertThat(first.legal()).isTrue();

        // 不同 requestId、不同航班号，但同一成员同一报到时刻：视为同时段重复，按幂等返回首条。
        ReportResponse duplicate = service.report(report("REQ-SLOT-2", "CK", "CA702",
                "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z"));
        assertThat(duplicate.replayed()).isTrue();
        assertThat(duplicate.requestId()).isEqualTo("REQ-SLOT-1");
        assertThat(duplicate.flightNo()).isEqualTo("CA701");
        assertThat(countDuties()).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdActsAsIdempotentReplay() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReportResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.report(report("REQ-SAME-ID", "CD", "CA800",
                            "2026-03-10T06:00:00Z", "2026-03-10T19:00:00Z"));
                }));
            }
            start.countDown();
            for (Future<ReportResponse> future : futures) {
                ReportResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.legal()).isTrue();
                assertThat(response.requestId()).isEqualTo("REQ-SAME-ID");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record WHERE request_id = 'REQ-SAME-ID'",
                Integer.class)).isEqualTo(1);
    }

    // ---- 延误延长与每日 2 小时上限 ----

    @Test
    void delayExactlyTwoHoursPerDayIsLegalAndOverflowsViolates() {
        // 同一 UTC 日历日内两班间隔 12 小时，首班延长 60 分钟后仍有 11 小时休息，隔离每日延长上限规则。
        service.report(report("REQ-D1", "CE", "CA900",
                "2026-03-10T06:00:00Z", "2026-03-10T08:00:00Z"));
        service.report(report("REQ-D2", "CE", "CA901",
                "2026-03-10T20:00:00Z", "2026-03-10T22:00:00Z"));

        // 两条各延长 60 分钟，当日累计恰 120 分钟，均合法。
        DelayResponse first = service.submitDelay(new DelayRequest("REQ-D1", 60));
        assertThat(first.applied()).isTrue();
        assertThat(first.dailyTotal()).isEqualTo(60);
        assertThat(first.legal()).isTrue();
        DelayResponse second = service.submitDelay(new DelayRequest("REQ-D2", 60));
        assertThat(second.dailyTotal()).isEqualTo(120);
        assertThat(second.legal()).isTrue();

        // 再多 1 分钟，当日累计 121，违规。
        DelayResponse overflow = service.submitDelay(new DelayRequest("REQ-D2", 1));
        assertThat(overflow.dailyTotal()).isEqualTo(121);
        assertThat(overflow.legal()).isFalse();
        assertThat(overflow.reasons()).containsExactly("M_DAILY_DELAY_LIMIT");
    }

    @Test
    void appliedDelayFlipsMemberAndGroupVerdictConsistently() {
        BatchCheckRequest request = new BatchCheckRequest("B3", List.of(
                new BatchCheckRequest.MemberDuty("REQ-G1", "CF", "CA110",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T18:00:00Z")),
                new BatchCheckRequest.MemberDuty("REQ-G2", "CG", "CA111",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T12:00:00Z"))));
        assertThat(service.checkBatch(request).legal()).isTrue();

        // CF 的执勤延长 121 分钟后 FDP 达 13 小时 1 分而违规（同日累计也超 120，记两类原因）。
        DelayResponse delayed = service.submitDelay(new DelayRequest("REQ-G1", 121));
        assertThat(delayed.applied()).isTrue();
        assertThat(delayed.legal()).isFalse();
        assertThat(delayed.reasons()).contains("M_FDP_LIMIT", "M_DAILY_DELAY_LIMIT");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM duty_record WHERE request_id = 'REQ-G1'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM batch_member WHERE batch_id = 'B3' AND crew_id = 'CF'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM check_batch WHERE batch_id = 'B3'", Integer.class)).isZero();
    }

    @Test
    void delayOnEarlierDutyCascadesToLaterDutyRestVerdictAndBatch() {
        // 两班原计划间隔恰 10 小时（合法）；首班延长 1 分钟后次班休息不足 10 小时，须级联重判。
        BatchCheckRequest request = new BatchCheckRequest("B7", List.of(
                new BatchCheckRequest.MemberDuty("REQ-C1", "CJ", "CA140",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T16:00:00Z")),
                new BatchCheckRequest.MemberDuty("REQ-C2", "CJ", "CA141",
                        utc("2026-03-11T02:00:00Z"), utc("2026-03-11T05:00:00Z"))));
        BatchCheckResponse checked = service.checkBatch(request);
        assertThat(checked.legal()).isTrue();

        DelayResponse delayed = service.submitDelay(new DelayRequest("REQ-C1", 1));
        assertThat(delayed.applied()).isTrue();
        assertThat(delayed.legal()).isTrue();

        // 次班被级联重判为休息不足；成员行与整组结论同步翻转。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM duty_record WHERE request_id = 'REQ-C2'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reasons FROM duty_record WHERE request_id = 'REQ-C2'", String.class))
                .isEqualTo("M_REST_LIMIT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM check_batch WHERE batch_id = 'B7'", Integer.class)).isZero();
    }

    // ---- 封板终态 ----

    @Test
    void finalizedVerdictIsTerminalAndLateDelayOnlyRecorded() {
        BatchCheckRequest request = new BatchCheckRequest("B4", List.of(
                new BatchCheckRequest.MemberDuty("REQ-F1", "CH", "CA120",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T12:00:00Z"))));
        service.checkBatch(request);

        FinalizeResponse finalized = service.finalizeBatch("B4");
        assertThat(finalized.legal()).isTrue();
        assertThat(finalized.replayed()).isFalse();

        // 重复封板幂等成功。
        FinalizeResponse again = service.finalizeBatch("B4");
        assertThat(again.replayed()).isTrue();
        assertThat(again.legal()).isTrue();

        // 封板后重校验被拒绝（改签须走新批次）。
        assertThatThrownBy(() -> service.checkBatch(request))
                .isInstanceOf(BatchFinalizedException.class);

        // 封板后补交延误仅记为迟到事件，不推翻结论。
        DelayResponse late = service.submitDelay(new DelayRequest("REQ-F1", 300));
        assertThat(late.applied()).isFalse();
        assertThat(late.batchFinalized()).isTrue();
        assertThat(late.legal()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal FROM duty_record WHERE request_id = 'REQ-F1'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delay_minutes FROM duty_record WHERE request_id = 'REQ-F1'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delay_event WHERE request_id = 'REQ-F1' AND applied = 0",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM check_batch WHERE batch_id = 'B4'", String.class))
                .isEqualTo("FINALIZED");
    }

    @Test
    void finalizeUnknownBatchReturns404() {
        assertThatThrownBy(() -> service.finalizeBatch("NO-SUCH-BATCH"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reportIntoFinalizedBatchRejectedAndNewBatchAllowsRebook() {
        BatchCheckRequest request = new BatchCheckRequest("B5", List.of(
                new BatchCheckRequest.MemberDuty("REQ-Z1", "CI", "CA130",
                        utc("2026-03-10T06:00:00Z"), utc("2026-03-10T12:00:00Z"))));
        service.checkBatch(request);
        service.finalizeBatch("B5");

        assertThatThrownBy(() -> service.report(
                new ReportRequest("REQ-Z2", "B5", "CI", "CA131",
                        utc("2026-03-11T06:00:00Z"), utc("2026-03-11T12:00:00Z"))))
                .isInstanceOf(BatchFinalizedException.class);

        // 改签走新批次可正常受理。
        BatchCheckResponse rebook = service.checkBatch(new BatchCheckRequest("B6", List.of(
                new BatchCheckRequest.MemberDuty("REQ-Z3", "CI", "CA132",
                        utc("2026-03-11T06:00:00Z"), utc("2026-03-11T12:00:00Z")))));
        assertThat(rebook.legal()).isTrue();
    }

    private int countDuties() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM duty_record", Integer.class);
        return count == null ? 0 : count;
    }
}
