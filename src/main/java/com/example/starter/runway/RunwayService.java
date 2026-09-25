package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 跑道临时关闭巡检与开放决策服务：状态流转、幂等、并发与事务边界在此实现。
 */
@Service
public class RunwayService {

    private static final String SCOPE_REPORT = "report";
    private static final String SCOPE_ASSESSMENT = "assessment";
    private static final String SCOPE_REOPEN = "reopen";

    private final RunwayEventRepository eventRepository;
    private final InspectionRepository inspectionRepository;
    private final AssessmentRepository assessmentRepository;
    private final NotamRepository notamRepository;
    private final WeatherRepository weatherRepository;
    private final RunwayProperties properties;
    private final IdempotencyExecutor idempotencyExecutor;
    private final Clock clock;

    public RunwayService(RunwayEventRepository eventRepository,
                         InspectionRepository inspectionRepository,
                         AssessmentRepository assessmentRepository,
                         NotamRepository notamRepository,
                         WeatherRepository weatherRepository,
                         RunwayProperties properties,
                         IdempotencyExecutor idempotencyExecutor,
                         Clock clock) {
        this.eventRepository = eventRepository;
        this.inspectionRepository = inspectionRepository;
        this.assessmentRepository = assessmentRepository;
        this.notamRepository = notamRepository;
        this.weatherRepository = weatherRepository;
        this.properties = properties;
        this.idempotencyExecutor = idempotencyExecutor;
        this.clock = clock;
    }

    /**
     * 接报：即触发临时关闭并同步发布关闭通告；requestId 幂等；同一跑道唯一进行中事件。
     */
    public ReportResponse report(ReportRequest request) {
        String fingerprint = fingerprint(SCOPE_REPORT, request.requestId(), request.runwayId(),
                request.eventType().name(), request.reportedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_REPORT, request.requestId(), fingerprint,
                    () -> doReport(request), ReportResponse.class);
        } catch (DuplicateKeyException e) {
            ReportResponse replayed = idempotencyExecutor.tryReplay(SCOPE_REPORT, request.requestId(),
                    fingerprint, ReportResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isActiveEventConflict(e)) {
                throw ApiException.conflict("RUNWAY_ACTIVE_EVENT_EXISTS", "同一跑道已存在进行中的关闭事件");
            }
            throw e;
        }
    }

    private ReportResponse doReport(ReportRequest request) {
        Instant now = clock.instant();
        Instant closedAt = request.reportedAt();
        Instant deadline = closedAt.plus(properties.inspectionStartLimitMinutes(), ChronoUnit.MINUTES);
        RunwayEvent event = new RunwayEvent(0L, request.runwayId(), request.eventType(), EventStatus.CLOSED,
                request.reportedAt(), closedAt, deadline, false, null, request.requestId(), now, now);
        event = eventRepository.insert(event, 1);
        String notamNo = "NTM-C-" + event.id();
        notamRepository.insert(new NotamRecord(0L, event.id(), event.runwayId(), NotamAction.CLOSE,
                notamNo, closedAt));
        return new ReportResponse(event.id(), event.runwayId(), event.status().name(), closedAt, deadline, notamNo);
    }

    /**
     * 巡检开始：恰满15分钟未开始判定迟延并升级；低能见度须双人，单人记录不生效；
     * 归档后迟到巡检仅登记迟到事件。
     */
    @Transactional
    public InspectionResponse startInspection(long eventId, InspectionRequest request) {
        RunwayEvent event = mustFind(eventId);
        Instant now = clock.instant();
        if (event.status() == EventStatus.ARCHIVED) {
            InspectionRecord lateRecord = inspectionRepository.insert(new InspectionRecord(0L, eventId,
                    request.startedAt(), request.inspectorCount(), true, true, now));
            return new InspectionResponse(lateRecord.id(), eventId, true, event.escalated(), true,
                    event.status().name());
        }
        if (event.status() == EventStatus.REOPENED) {
            throw ApiException.conflict("EVENT_NOT_ACTIVE", "事件已恢复开放，不再受理巡检");
        }
        boolean late = !request.startedAt().isBefore(event.inspectionDeadline());
        boolean lowVisibility = weatherRepository.findLatestAtOrBefore(event.runwayId(), request.startedAt())
                .map(reading -> reading.rvrMeters() < properties.lowVisibilityRvrThreshold())
                .orElse(false);
        if (lowVisibility && request.inspectorCount() < 2) {
            throw ApiException.unprocessable("LOW_VISIBILITY_REQUIRES_DUAL",
                    "低能见度期间巡检须双人执行，单人记录不生效");
        }
        InspectionRecord record = inspectionRepository.insert(new InspectionRecord(0L, eventId,
                request.startedAt(), request.inspectorCount(), late, false, now));
        boolean escalated = event.escalated() || late;
        EventStatus newStatus = event.status() == EventStatus.CLOSED ? EventStatus.INSPECTING : event.status();
        eventRepository.updateStatus(eventId, newStatus, escalated, event.assessmentType(), false, now);
        return new InspectionResponse(record.id(), eventId, late, escalated, false, newStatus.name());
    }

    /**
     * 巡检评估：摩擦系数低于0.35持续关闭，恰0.35合格；requestId 幂等。
     */
    public AssessmentResponse assess(long eventId, AssessmentRequest request) {
        String fingerprint = fingerprint(SCOPE_ASSESSMENT, request.requestId(), String.valueOf(eventId),
                request.frictionCoefficient().toPlainString(), request.assessmentType().name(),
                request.assessedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_ASSESSMENT, request.requestId(), fingerprint,
                    () -> doAssess(eventId, request), AssessmentResponse.class);
        } catch (DuplicateKeyException e) {
            AssessmentResponse replayed = idempotencyExecutor.tryReplay(SCOPE_ASSESSMENT, request.requestId(),
                    fingerprint, AssessmentResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private AssessmentResponse doAssess(long eventId, AssessmentRequest request) {
        RunwayEvent event = mustFind(eventId);
        requireActive(event, "评估");
        boolean passed = request.frictionCoefficient().compareTo(properties.frictionPassThreshold()) >= 0;
        List<AssessmentRecord> history = assessmentRepository.findByEventIdOrderByAssessedAt(eventId);
        boolean retest = history.stream().anyMatch(a -> a.assessmentType() == AssessmentType.REPAIR_REQUIRED);
        AssessmentRecord record = assessmentRepository.insert(new AssessmentRecord(0L, eventId,
                request.frictionCoefficient(), request.assessmentType(), request.assessedAt(), passed, retest,
                request.requestId(), clock.instant()));
        eventRepository.updateStatus(eventId, EventStatus.ASSESSED, event.escalated(), request.assessmentType(),
                false, clock.instant());
        return new AssessmentResponse(record.id(), eventId, passed, retest, EventStatus.ASSESSED.name());
    }

    /**
     * 恢复开放：与航行通告发布在同一事务内完成；修复类须复测合格；requestId 幂等。
     */
    public ReopenResponse reopen(long eventId, ReopenRequest request) {
        String fingerprint = fingerprint(SCOPE_REOPEN, request.requestId(), String.valueOf(eventId),
                request.reopenedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_REOPEN, request.requestId(), fingerprint,
                    () -> doReopen(eventId, request), ReopenResponse.class);
        } catch (DuplicateKeyException e) {
            ReopenResponse replayed = idempotencyExecutor.tryReplay(SCOPE_REOPEN, request.requestId(),
                    fingerprint, ReopenResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private ReopenResponse doReopen(long eventId, ReopenRequest request) {
        RunwayEvent event = mustFind(eventId);
        if (event.status() == EventStatus.ARCHIVED) {
            throw ApiException.conflict("EVENT_ARCHIVED", "事件已归档，不得恢复开放");
        }
        if (event.status() == EventStatus.REOPENED) {
            throw ApiException.conflict("EVENT_ALREADY_REOPENED", "事件已恢复开放");
        }
        List<AssessmentRecord> history = assessmentRepository.findByEventIdOrderByAssessedAt(eventId);
        if (history.isEmpty()) {
            throw ApiException.unprocessable("ASSESSMENT_REQUIRED", "尚无评估记录，不得恢复开放");
        }
        int lastRepairIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).assessmentType() == AssessmentType.REPAIR_REQUIRED) {
                lastRepairIndex = i;
            }
        }
        if (lastRepairIndex >= 0) {
            boolean passAfterRepair = history.subList(lastRepairIndex + 1, history.size()).stream()
                    .anyMatch(AssessmentRecord::passed);
            if (!passAfterRepair) {
                throw ApiException.unprocessable("REPAIR_RETEST_REQUIRED", "修复类评估须复测合格方可开放");
            }
        }
        if (!history.getLast().passed()) {
            throw ApiException.unprocessable("FRICTION_BELOW_THRESHOLD", "摩擦系数低于0.35，须持续关闭");
        }
        Instant now = clock.instant();
        eventRepository.updateStatus(eventId, EventStatus.REOPENED, event.escalated(), event.assessmentType(),
                true, now);
        String notamNo = "NTM-R-" + eventId;
        notamRepository.insert(new NotamRecord(0L, eventId, event.runwayId(), NotamAction.REOPEN,
                notamNo, request.reopenedAt()));
        return new ReopenResponse(eventId, EventStatus.REOPENED.name(), request.reopenedAt(), notamNo);
    }

    /**
     * 归档：终态；重复归档返回幂等成功。
     */
    @Transactional
    public ArchiveResponse archive(long eventId) {
        RunwayEvent event = mustFind(eventId);
        if (event.status() == EventStatus.ARCHIVED) {
            return new ArchiveResponse(eventId, EventStatus.ARCHIVED.name(), true);
        }
        if (event.status() != EventStatus.REOPENED && event.status() != EventStatus.ASSESSED) {
            throw ApiException.conflict("EVENT_NOT_ARCHIVABLE", "仅恢复开放或持续关闭（已评估）状态可归档");
        }
        eventRepository.updateStatus(eventId, EventStatus.ARCHIVED, event.escalated(), event.assessmentType(),
                true, clock.instant());
        return new ArchiveResponse(eventId, EventStatus.ARCHIVED.name(), false);
    }

    /**
     * 事件详情：状态时间线由事件、巡检、评估与通告记录复算。
     */
    @Transactional(readOnly = true)
    public EventDetailResponse getDetail(long eventId) {
        RunwayEvent event = mustFind(eventId);
        List<TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new TimelineEntry("REPORTED", event.reportedAt(),
                "接报" + event.eventType() + "，触发临时关闭"));
        for (NotamRecord notam : notamRepository.findByEventId(eventId)) {
            timeline.add(new TimelineEntry(notam.action() == NotamAction.CLOSE ? "CLOSED_NOTAM" : "REOPEN_NOTAM",
                    notam.publishedAt(), "航行通告 " + notam.noticeNo()));
        }
        boolean escalatedMarked = false;
        for (InspectionRecord inspection : inspectionRepository.findByEventId(eventId)) {
            if (inspection.recordedAfterArchive()) {
                timeline.add(new TimelineEntry("LATE_INSPECTION_RECORDED", inspection.startedAt(),
                        "归档后迟到巡检记录，仅登记"));
                continue;
            }
            timeline.add(new TimelineEntry("INSPECTION_STARTED", inspection.startedAt(),
                    "巡检开始，人数" + inspection.inspectorCount() + (inspection.late() ? "，迟延" : "")));
            if (inspection.late() && !escalatedMarked) {
                timeline.add(new TimelineEntry("ESCALATED", inspection.startedAt(), "巡检迟延，事件升级"));
                escalatedMarked = true;
            }
        }
        for (AssessmentRecord assessment : assessmentRepository.findByEventIdOrderByAssessedAt(eventId)) {
            timeline.add(new TimelineEntry("ASSESSED", assessment.assessedAt(),
                    "摩擦系数" + assessment.frictionCoefficient().toPlainString()
                            + (assessment.passed() ? "，合格" : "，不合格持续关闭")
                            + (assessment.retest() ? "（复测）" : "")));
        }
        if (event.status() == EventStatus.REOPENED) {
            notamRepository.findByEventId(eventId).stream()
                    .filter(n -> n.action() == NotamAction.REOPEN)
                    .findFirst()
                    .ifPresent(n -> timeline.add(new TimelineEntry("REOPENED", n.publishedAt(), "恢复开放")));
        }
        if (event.status() == EventStatus.ARCHIVED) {
            timeline.add(new TimelineEntry("ARCHIVED", event.updatedAt(), "事件归档（终态）"));
        }
        timeline.sort(Comparator.comparing(TimelineEntry::at));
        return new EventDetailResponse(event.id(), event.runwayId(), event.eventType().name(),
                event.status().name(), event.reportedAt(), event.closedAt(), event.inspectionDeadline(),
                event.escalated(), timeline);
    }

    /**
     * 摩擦系数轨迹：按评估时间排序。
     */
    @Transactional(readOnly = true)
    public List<FrictionPoint> getFrictionTrajectory(long eventId) {
        mustFind(eventId);
        return assessmentRepository.findByEventIdOrderByAssessedAt(eventId).stream()
                .map(a -> new FrictionPoint(a.id(), a.frictionCoefficient(), a.assessmentType().name(),
                        a.passed(), a.retest(), a.assessedAt()))
                .toList();
    }

    /**
     * 通告记录。
     */
    @Transactional(readOnly = true)
    public List<NotamView> getNotams(long eventId) {
        mustFind(eventId);
        return notamRepository.findByEventId(eventId).stream()
                .map(n -> new NotamView(n.id(), n.eventId(), n.action().name(), n.noticeNo(), n.publishedAt()))
                .toList();
    }

    /**
     * 上报气象读数（合成数据）。
     */
    @Transactional
    public WeatherResponse addWeatherReading(WeatherRequest request) {
        WeatherReading saved = weatherRepository.insert(new WeatherReading(0L, request.runwayId(),
                request.rvrMeters(), request.recordedAt()));
        return new WeatherResponse(saved.id(), saved.runwayId(), saved.rvrMeters(), saved.recordedAt());
    }

    /**
     * 按跑道查询气象读数。
     */
    @Transactional(readOnly = true)
    public List<WeatherResponse> listWeatherReadings(String runwayId) {
        return weatherRepository.findByRunwayId(runwayId).stream()
                .map(w -> new WeatherResponse(w.id(), w.runwayId(), w.rvrMeters(), w.recordedAt()))
                .toList();
    }

    private RunwayEvent mustFind(long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("跑道事件不存在: " + eventId));
    }

    private void requireActive(RunwayEvent event, String operation) {
        if (event.status() == EventStatus.ARCHIVED) {
            throw ApiException.conflict("EVENT_ARCHIVED", "事件已归档，不再受理" + operation);
        }
        if (event.status() == EventStatus.REOPENED) {
            throw ApiException.conflict("EVENT_NOT_ACTIVE", "事件已恢复开放，不再受理" + operation);
        }
    }

    private boolean isActiveEventConflict(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("uk_runway_event_active");
    }

    private String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
