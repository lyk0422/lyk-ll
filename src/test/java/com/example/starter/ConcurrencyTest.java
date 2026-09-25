package com.example.starter;

import com.example.starter.common.ApiException;
import com.example.starter.report.ReportDtos;
import com.example.starter.report.ReportService;
import com.example.starter.schedule.ScheduleDtos;
import com.example.starter.schedule.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：同站同窗并发申请仅一个成功；漏报标记与迟到回报竞态下形成唯一终态。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ReportService reportService;

    @Test
    void concurrentSameStationSameWindowOnlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ScheduleService.StoredResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return scheduleService.submit(new ScheduleDtos.SubmitRequest(
                            "REQ-CC-" + idx, "SAT-A", "GS-BJ", "W0001", 3));
                } catch (ApiException e) {
                    return new ScheduleService.StoredResponse(e.getStatus(), e.getCode());
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<ScheduleService.StoredResponse> future : futures) {
            ScheduleService.StoredResponse response = future.get(20, TimeUnit.SECONDS);
            if (response.httpStatus() == 200) {
                ok++;
            } else if (response.httpStatus() == 409) {
                conflict++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, ok, "同站同窗并发申请只允许一个成功");
        assertEquals(threads - 1, conflict);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE status = 'SCHEDULED'", Integer.class));
    }

    @Test
    void missedMarkAndLateReportRaceYieldUniqueFinalState() throws Exception {
        // 多轮竞态：每轮断言终态唯一（要么已回报无漏报，要么漏报且仅记一条迟到事件）
        for (int round = 0; round < 5; round++) {
            scheduleService.submit(new ScheduleDtos.SubmitRequest(
                    "REQ-RACE-" + round, "SAT-A", "GS-BJ", "W0008", 3));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ReportDtos.MarkMissedResult> markFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return reportService.markMissed(Instant.parse("2026-09-25T00:00:00Z"));
            });
            Future<ReportDtos.ReportResult> reportFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return reportService.report(
                        new ReportDtos.ReportRequest("W0008", "GS-BJ", "SUCCESS"));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            ReportDtos.MarkMissedResult markResult = markFuture.get(20, TimeUnit.SECONDS);
            ReportDtos.ReportResult reportResult = reportFuture.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM window_outcome", Integer.class),
                    "第 " + round + "轮：终态必须唯一");
            String state = jdbc.queryForObject("SELECT state FROM window_outcome", String.class);
            int lateEvents = jdbc.queryForObject("SELECT COUNT(*) FROM late_report_event", Integer.class);
            if ("MISSED".equals(state)) {
                assertEquals(1, markResult.marked(), "漏报方获胜：应标记一条漏报");
                assertEquals("LATE_RECORDED", reportResult.outcome(), "迟到回报只记事件");
                assertEquals(1, lateEvents, "漏报后迟到回报只记一条迟到事件");
            } else {
                assertEquals("REPORTED", state);
                assertEquals(0, markResult.marked(), "回报方获胜：不应标记漏报");
                assertEquals(0, lateEvents, "已回报不应产生迟到事件");
            }

            // 下一轮前清理业务数据
            jdbc.update("DELETE FROM late_report_event");
            jdbc.update("DELETE FROM window_outcome");
            jdbc.update("DELETE FROM schedule_entry");
            jdbc.update("DELETE FROM schedule_request");
        }
    }
}
