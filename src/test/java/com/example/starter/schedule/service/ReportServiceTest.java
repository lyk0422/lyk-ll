package com.example.starter.schedule.service;

import com.example.starter.schedule.AbstractIntegrationTest;
import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.model.EntryStatus;
import com.example.starter.schedule.model.EventType;
import com.example.starter.schedule.model.ReportStatus;
import com.example.starter.schedule.web.dto.ReportRequestDto;
import com.example.starter.schedule.web.dto.ReportResponseDto;
import com.example.starter.schedule.web.dto.ScheduleRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行回报去重、终态不可改、漏报扫描与漏报/迟到回报终态竞争。
 */
class ReportServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ReportService reportService;

    private long sat;
    private long station;

    @BeforeEach
    void setUp() {
        cleanAll();
        mutableClock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        sat = createSatellite("SAT-A");
        station = createStation("GS-1");
    }

    private long scheduleWindow(int sh, int sm, int eh, int em, String requestId) {
        ContactWindow w = window(sat, station, sh, sm, eh, em);
        var resp = scheduleService.submit(
                new ScheduleRequestDto(requestId, "SAT-A", "GS-1", w.id(), 3, false));
        assertThat(resp.outcome()).isEqualTo("SCHEDULED");
        return w.id();
    }

    @Test
    @DisplayName("回报按窗口+站去重：重复回报不新增记录且终态不可改")
    void duplicateReportDoesNotInsertOrChange() {
        long windowId = scheduleWindow(2, 0, 2, 15, "req-1");

        ReportResponseDto first = reportService.submit("GS-1",
                new ReportRequestDto(windowId, ReportStatus.SUCCESS, "首次成功"));
        ReportResponseDto duplicate = reportService.submit("GS-1",
                new ReportRequestDto(windowId, ReportStatus.FAILURE, "试图改为失败"));

        assertThat(first.duplicate()).isFalse();
        assertThat(first.reportId()).isNotNull();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.reportId()).isEqualTo(first.reportId());
        assertThat(duplicate.status()).isEqualTo("SUCCESS");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report WHERE station_id = ? AND window_id = ?",
                Integer.class, station, windowId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口结束未回报经扫描标记 MISSED；已回报窗口不漏报")
    void scanMarksMissedOnlyUnreported() {
        long unreported = scheduleWindow(1, 0, 1, 10, "req-missed");
        long reported = scheduleWindow(2, 0, 2, 15, "req-reported");
        reportService.submit("GS-1",
                new ReportRequestDto(reported, ReportStatus.PARTIAL, "部分成功"));

        mutableClock.setInstant(Instant.parse("2026-09-25T03:00:00Z"));
        int missed = reportService.scanMissed();
        assertThat(missed).isEqualTo(1);

        String missedStatus = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = ?",
                String.class, "req-missed");
        String reportedStatus = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = ?",
                String.class, "req-reported");
        assertThat(missedStatus).isEqualTo(EntryStatus.MISSED.name());
        assertThat(reportedStatus).isEqualTo(EntryStatus.SCHEDULED.name());
        // 重复扫描不产生新标记
        assertThat(reportService.scanMissed()).isZero();
    }

    @Test
    @DisplayName("漏报后的迟到回报只记 LATE_REPORT 事件，不改漏报事实")
    void lateReportAfterMissedRecordsEventOnly() {
        long windowId = scheduleWindow(1, 0, 1, 10, "req-1");
        mutableClock.setInstant(Instant.parse("2026-09-25T02:00:00Z"));
        assertThat(reportService.scanMissed()).isEqualTo(1);

        ReportResponseDto late = reportService.submit("GS-1",
                new ReportRequestDto(windowId, ReportStatus.SUCCESS, "迟到的成功回报"));

        assertThat(late.late()).isTrue();
        assertThat(late.duplicate()).isFalse();
        assertThat(late.reportId()).isNull();

        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report WHERE station_id = ? AND window_id = ?",
                Integer.class, station, windowId);
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report_event WHERE station_id = ? AND window_id = ? AND event_type = ?",
                Integer.class, station, windowId, EventType.LATE_REPORT.name());
        String status = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = ?",
                String.class, "req-1");
        assertThat(reports).isZero();
        assertThat(events).isEqualTo(1);
        assertThat(status).isEqualTo(EntryStatus.MISSED.name());
    }
}
