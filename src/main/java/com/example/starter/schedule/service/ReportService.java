package com.example.starter.schedule.service;

import com.example.starter.schedule.domain.ContactReport;
import com.example.starter.schedule.domain.GroundStation;
import com.example.starter.schedule.domain.ScheduleEntry;
import com.example.starter.schedule.model.EntryStatus;
import com.example.starter.schedule.model.EventType;
import com.example.starter.schedule.repository.MasterDataRepository;
import com.example.starter.schedule.repository.ReportRepository;
import com.example.starter.schedule.repository.ScheduleRepository;
import com.example.starter.schedule.web.ApiException;
import com.example.starter.schedule.web.dto.ReportRequestDto;
import com.example.starter.schedule.web.dto.ReportResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 执行回报与漏报终态服务。
 *
 * <p>终态竞争规则：回报按“窗口＋站”唯一去重；窗口结束后未回报由漏报扫描标记 MISSED。
 * 漏报标记与迟到回报并发时，通过条目行锁串行化形成唯一终态：
 * 漏报先成则迟到回报只记 LATE_REPORT 事件，不改漏报事实。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final MasterDataRepository masterData;
    private final ScheduleRepository schedules;
    private final ReportRepository reports;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReportService(MasterDataRepository masterData,
                         ScheduleRepository schedules,
                         ReportRepository reports,
                         Clock clock,
                         ObjectMapper objectMapper) {
        this.masterData = masterData;
        this.schedules = schedules;
        this.reports = reports;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交执行回报。
     */
    @Transactional
    public ReportResponseDto submit(String stationCode, ReportRequestDto dto) {
        Instant now = Instant.now(clock);
        GroundStation station = masterData.findStationByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("地面站不存在: " + stationCode));

        // 锁定该“站＋窗口”的最新编排条目，漏报扫描与回报在此行上串行化
        ScheduleEntry entry = schedules.findLatestEntryByWindowForUpdate(station.id(), dto.windowId())
                .orElseThrow(() -> ApiException.notFound(
                        "该站该窗口不存在编排条目，无法回报: windowId=" + dto.windowId()));

        // 已有回报：按窗口去重，返回首次回报内容，不新增记录
        Optional<ContactReport> existing = reports.findByWindowForUpdate(station.id(), dto.windowId());
        if (existing.isPresent()) {
            ContactReport r = existing.get();
            return new ReportResponseDto(dto.windowId(), stationCode, r.getId(),
                    r.getStatus().name(), r.getReportedAt(), true, false);
        }

        if (entry.getStatus() == EntryStatus.MISSED) {
            // 漏报终态已成：迟到回报只记事件，不改漏报事实
            String payload = writeLatePayload(dto, now);
            long eventId = reports.insertEvent(station.id(), dto.windowId(),
                    EventType.LATE_REPORT, payload, now);
            log.info("漏报后收到迟到回报 station={} window={} eventId={}",
                    stationCode, dto.windowId(), eventId);
            return new ReportResponseDto(dto.windowId(), stationCode, null,
                    dto.status().name(), now, false, true);
        }

        if (entry.getStatus() != EntryStatus.SCHEDULED) {
            throw ApiException.unprocessable("ENTRY_NOT_REPORTABLE",
                    "条目当前状态为 " + entry.getStatus() + "，不可提交执行回报；补救观测须申请新窗口");
        }

        ContactReport report = new ContactReport();
        report.setStationId(station.id());
        report.setWindowId(dto.windowId());
        report.setEntryId(entry.getId());
        report.setStatus(dto.status());
        report.setRemark(dto.remark());
        report.setReportedAt(now);
        try {
            long reportId = reports.tryInsert(report);
            return new ReportResponseDto(dto.windowId(), stationCode, reportId,
                    dto.status().name(), now, false, false);
        } catch (DuplicateKeyException dup) {
            // 并发下唯一索引兜底去重
            ContactReport r = reports.findByWindowForUpdate(station.id(), dto.windowId())
                    .orElseThrow();
            return new ReportResponseDto(dto.windowId(), stationCode, r.getId(),
                    r.getStatus().name(), r.getReportedAt(), true, false);
        }
    }

    /**
     * 漏报扫描：窗口结束时刻已过仍未回报的已编排条目标记 MISSED。
     * 条目行锁与回报事务互斥；持锁后复查“窗口＋站”是否已有回报。
     *
     * @return 本次新标记的漏报条目数
     */
    @Transactional
    public int scanMissed() {
        Instant now = Instant.now(clock);
        int count = 0;
        for (ScheduleEntry entry : schedules.findExpiredScheduledForUpdate(now)) {
            if (reports.countReports(entry.getStationId(), entry.getWindowId()) > 0) {
                continue;
            }
            int rows = schedules.casMissed(entry.getId(), now);
            if (rows == 1) {
                count++;
                log.info("窗口结束未回报，标记漏报 entryId={} station={} window={} endTime={}",
                        entry.getId(), entry.getStationId(), entry.getWindowId(), entry.getEndTime());
            }
        }
        return count;
    }

    private String writeLatePayload(ReportRequestDto dto, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", dto.status().name());
        payload.put("remark", dto.remark());
        payload.put("receivedAt", now.toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("迟到回报事件序列化失败", e);
        }
    }
}
