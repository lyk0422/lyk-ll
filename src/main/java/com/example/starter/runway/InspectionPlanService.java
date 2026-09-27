package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 例行跑道巡检窗口编排与紧急关闭抢占服务。
 * 计划发布冻结航班日历版本与冲突证据；日历变更使未开始计划待重审；
 * 紧急接报同事务抢占该跑道例行计划；发布/开始与紧急接报经跑道级日历行锁串行，跨跑道互不阻塞。
 */
@Service
public class InspectionPlanService {

    private static final String SCOPE_PLAN_PUBLISH = "plan-publish";
    private static final String SCOPE_PLAN_START = "plan-start";
    private static final String SCOPE_CHECK_REPORT = "check-report";
    private static final String SCOPE_CALENDAR_CHANGE = "calendar-change";

    private final InspectionPlanRepository planRepository;
    private final PlanCheckReportRepository checkReportRepository;
    private final FlightCalendarRepository calendarRepository;
    private final RunwayEventRepository eventRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InspectionPlanService(InspectionPlanRepository planRepository,
                                 PlanCheckReportRepository checkReportRepository,
                                 FlightCalendarRepository calendarRepository,
                                 RunwayEventRepository eventRepository,
                                 IdempotencyExecutor idempotencyExecutor,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.planRepository = planRepository;
        this.checkReportRepository = checkReportRepository;
        this.calendarRepository = calendarRepository;
        this.eventRepository = eventRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 计划申请发布：校验窗口与航班占用窗（正长度相交拒绝、端点相接合法），
     * 冻结日历版本与冲突证据；同跑道同窗仅一个获准计划；requestId 幂等。
     */
    public PlanResponse publish(PlanPublishRequest request) {
        String fingerprint = StructuredFingerprint.of(publishParts(request));
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_PUBLISH, request.requestId(), fingerprint,
                    () -> doPublish(request), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_PUBLISH, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isWindowOccupiedConflict(e)) {
                throw ApiException.conflict("PLAN_WINDOW_OCCUPIED", "同一跑道同一窗口已存在获准计划");
            }
            throw e;
        }
    }

    private PlanResponse doPublish(PlanPublishRequest request) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw ApiException.badRequest("WINDOW_INVALID", "窗口终点必须晚于起点（UTC 半开窗口）");
        }
        for (FlightWindow window : request.flightWindows()) {
            if (!window.end().isAfter(window.start())) {
                throw ApiException.badRequest("FLIGHT_WINDOW_INVALID", "航班占用窗终点必须晚于起点");
            }
        }
        Instant inspectionEnd = request.windowStart().plus(request.inspectionMinutes(), ChronoUnit.MINUTES);
        if (inspectionEnd.isAfter(request.windowEnd())) {
            throw ApiException.unprocessable("DURATION_EXCEEDS_WINDOW", "巡检时长须完整落在窗口内");
        }
        for (FlightWindow window : request.flightWindows()) {
            boolean positiveOverlap = request.windowStart().isBefore(window.end())
                    && window.start().isBefore(inspectionEnd);
            if (positiveOverlap) {
                throw ApiException.unprocessable("FLIGHT_WINDOW_CONFLICT",
                        "巡检占用区间与航班占用窗存在正长度相交");
            }
        }
        Instant now = clock.instant();
        FlightCalendar calendar = calendarRepository.getOrCreateForUpdate(request.runwayId(), now);
        if (eventRepository.existsActiveByRunwayId(request.runwayId())) {
            throw ApiException.unprocessable("RUNWAY_EMERGENCY_ACTIVE", "跑道存在进行中紧急关闭事件，不得发布例行计划");
        }
        String evidence = writeJson(conflictEvidence(request, calendar.version(), inspectionEnd, now));
        InspectionPlan plan = new InspectionPlan(0L, request.runwayId(), request.windowStart(),
                request.windowEnd(), request.inspectionMinutes(), writeJson(request.flightWindows()),
                calendar.version(), evidence, PlanStatus.APPROVED, 1, request.requestId(), null, null, now, now);
        return toResponse(planRepository.insert(plan, 1));
    }

    /**
     * 开始巡检：重新确认日历版本（待重审计划按当前版本重冻结），
     * 跑道须无进行中紧急关闭事件；expectedVersion 乐观锁；requestId 幂等。
     */
    public PlanResponse start(long planId, PlanStartRequest request) {
        String fingerprint = StructuredFingerprint.of(List.of(
                request.requestId(), String.valueOf(planId), String.valueOf(request.expectedVersion()),
                String.valueOf(request.confirmedCalendarVersion()), request.startedAt().toString()));
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_START, request.requestId(), fingerprint,
                    () -> doStart(planId, request), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_START, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanResponse doStart(long planId, PlanStartRequest request) {
        InspectionPlan plan = mustFind(planId);
        requireVersion(plan, request.expectedVersion());
        if (plan.status() != PlanStatus.APPROVED && plan.status() != PlanStatus.PENDING_REVIEW) {
            throw ApiException.unprocessable("PLAN_NOT_STARTABLE", "当前状态不得开始巡检: " + plan.status());
        }
        Instant now = clock.instant();
        FlightCalendar calendar = calendarRepository.getOrCreateForUpdate(plan.runwayId(), now);
        if (calendar.version() != request.confirmedCalendarVersion()) {
            throw ApiException.unprocessable("CALENDAR_VERSION_MISMATCH",
                    "确认的日历版本与当前版本不一致，须重审后再开始");
        }
        if (eventRepository.existsActiveByRunwayId(plan.runwayId())) {
            throw ApiException.unprocessable("RUNWAY_EMERGENCY_ACTIVE", "跑道存在进行中紧急关闭事件，不得开始巡检");
        }
        Instant inspectionEnd = request.startedAt().plus(plan.inspectionMinutes(), ChronoUnit.MINUTES);
        if (request.startedAt().isBefore(plan.windowStart()) || inspectionEnd.isAfter(plan.windowEnd())) {
            throw ApiException.unprocessable("INSPECTION_OUTSIDE_WINDOW", "开始时刻须使巡检时长完整落在窗口内");
        }
        // 待重审计划按当前日历版本重冻结；获准计划冻结版本与当前一致（不变式）
        boolean updated = planRepository.updateWithVersion(planId, plan.version(), PlanStatus.IN_PROGRESS,
                calendar.version(), request.startedAt(), plan.completedItems(), false, now);
        if (!updated) {
            throw versionConflict();
        }
        return toResponse(planRepository.findById(planId).orElseThrow());
    }

    /**
     * 检查回报：进行中计划累积检查项，完结回报仅关闭计划（不触发跑道开放）；
     * 被抢占（取消/中止）后的回报仅登记迟到，不开放跑道；expectedVersion 乐观锁；requestId 幂等。
     */
    public CheckReportResponse reportCheck(long planId, CheckReportRequest request) {
        List<String> parts = new ArrayList<>(List.of(
                request.requestId(), String.valueOf(planId), String.valueOf(request.expectedVersion()),
                request.reportedAt().toString(), String.valueOf(request.complete()),
                String.valueOf(request.completedItems().size())));
        parts.addAll(request.completedItems());
        String fingerprint = StructuredFingerprint.of(parts);
        try {
            return idempotencyExecutor.execute(SCOPE_CHECK_REPORT, request.requestId(), fingerprint,
                    () -> doReportCheck(planId, request), CheckReportResponse.class);
        } catch (DuplicateKeyException e) {
            CheckReportResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CHECK_REPORT,
                    request.requestId(), fingerprint, CheckReportResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private CheckReportResponse doReportCheck(long planId, CheckReportRequest request) {
        InspectionPlan plan = mustFind(planId);
        requireVersion(plan, request.expectedVersion());
        Instant now = clock.instant();
        if (plan.status() == PlanStatus.CANCELLED || plan.status() == PlanStatus.ABORTED) {
            // 被抢占后的完成回报只记录迟到，不得开放跑道
            PlanCheckReport late = checkReportRepository.insert(new PlanCheckReport(0L, planId,
                    request.reportedAt(), writeJson(request.completedItems()), request.complete(), true,
                    request.requestId(), now));
            return new CheckReportResponse(late.id(), planId, true, plan.status().name(), plan.version());
        }
        if (plan.status() != PlanStatus.IN_PROGRESS) {
            throw ApiException.unprocessable("PLAN_NOT_REPORTABLE", "当前状态不受理检查回报: " + plan.status());
        }
        PlanCheckReport report = checkReportRepository.insert(new PlanCheckReport(0L, planId,
                request.reportedAt(), writeJson(request.completedItems()), request.complete(), false,
                request.requestId(), now));
        String mergedItems = writeJson(mergeItems(plan.completedItems(), request.completedItems()));
        PlanStatus newStatus = request.complete() ? PlanStatus.COMPLETED : PlanStatus.IN_PROGRESS;
        boolean updated = planRepository.updateWithVersion(planId, plan.version(), newStatus,
                plan.calendarVersion(), plan.startedAt(), mergedItems, request.complete(), now);
        if (!updated) {
            throw versionConflict();
        }
        return new CheckReportResponse(report.id(), planId, false, newStatus.name(), plan.version() + 1);
    }

    /**
     * 航班日历变更：版本加一；尚未开始的获准计划转待重审，已开始计划不平移。
     */
    public CalendarChangeResponse changeCalendar(String runwayId, CalendarChangeRequest request) {
        String fingerprint = StructuredFingerprint.of(List.of(
                request.requestId(), runwayId, String.valueOf(request.expectedVersion()),
                request.changedAt().toString()));
        try {
            return idempotencyExecutor.execute(SCOPE_CALENDAR_CHANGE, request.requestId(), fingerprint,
                    () -> doChangeCalendar(runwayId, request), CalendarChangeResponse.class);
        } catch (DuplicateKeyException e) {
            CalendarChangeResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CALENDAR_CHANGE,
                    request.requestId(), fingerprint, CalendarChangeResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private CalendarChangeResponse doChangeCalendar(String runwayId, CalendarChangeRequest request) {
        Instant now = clock.instant();
        FlightCalendar calendar = calendarRepository.getOrCreateForUpdate(runwayId, now);
        if (calendar.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "日历版本不匹配: 期望" + request.expectedVersion() + "，当前" + calendar.version());
        }
        int newVersion = calendarRepository.incrementVersion(runwayId, request.changedAt());
        List<Long> affected = new ArrayList<>();
        for (InspectionPlan plan : planRepository.findActiveByRunwayId(runwayId)) {
            if (plan.status() != PlanStatus.APPROVED) {
                continue;
            }
            boolean updated = planRepository.updateWithVersion(plan.id(), plan.version(),
                    PlanStatus.PENDING_REVIEW, plan.calendarVersion(), plan.startedAt(),
                    plan.completedItems(), false, now);
            if (updated) {
                affected.add(plan.id());
            }
        }
        return new CalendarChangeResponse(runwayId, newVersion, affected);
    }

    /**
     * 日历影响查询：冻结日历版本落后于当前版本的非终态计划。
     */
    @Transactional(readOnly = true)
    public CalendarImpactResponse calendarImpact(String runwayId) {
        return calendarRepository.findByRunwayId(runwayId)
                .map(calendar -> new CalendarImpactResponse(runwayId, calendar.version(),
                        planRepository.findImpactedByRunwayId(runwayId, calendar.version()).stream()
                                .map(plan -> new PlanImpactView(plan.id(), plan.status().name(),
                                        plan.calendarVersion(), plan.windowStart(), plan.windowEnd()))
                                .toList()))
                .orElseGet(() -> new CalendarImpactResponse(runwayId, null, List.of()));
    }

    /**
     * 计划详情。
     */
    @Transactional(readOnly = true)
    public PlanResponse getPlan(long planId) {
        return toResponse(mustFind(planId));
    }

    /**
     * 紧急关闭抢占（与紧急接报同事务）：取消该跑道尚未开始计划，
     * 已开始计划转中止并保留已完成检查项；被抢占项不复活。
     */
    @Transactional
    public void preemptForEmergency(String runwayId, Instant now) {
        calendarRepository.getOrCreateForUpdate(runwayId, now);
        for (InspectionPlan plan : planRepository.findActiveByRunwayId(runwayId)) {
            PlanStatus preempted = switch (plan.status()) {
                case APPROVED, PENDING_REVIEW -> PlanStatus.CANCELLED;
                case IN_PROGRESS -> PlanStatus.ABORTED;
                default -> null;
            };
            if (preempted == null) {
                continue;
            }
            planRepository.updateWithVersion(plan.id(), plan.version(), preempted, plan.calendarVersion(),
                    plan.startedAt(), plan.completedItems(), true, now);
        }
    }

    private InspectionPlan mustFind(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "例行巡检计划不存在: " + planId));
    }

    private void requireVersion(InspectionPlan plan, long expectedVersion) {
        if (plan.version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private ApiException versionConflict() {
        return ApiException.conflict("VERSION_CONFLICT", "计划版本不匹配，请刷新后重试");
    }

    private boolean isWindowOccupiedConflict(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("uk_inspection_plan_active");
    }

    private List<String> publishParts(PlanPublishRequest request) {
        List<String> parts = new ArrayList<>(List.of(
                request.requestId(), request.runwayId(), request.windowStart().toString(),
                request.windowEnd().toString(), String.valueOf(request.inspectionMinutes()),
                String.valueOf(request.flightWindows().size())));
        for (FlightWindow window : request.flightWindows()) {
            parts.add(window.start().toString());
            parts.add(window.end().toString());
        }
        return parts;
    }

    private Map<String, Object> conflictEvidence(PlanPublishRequest request, int calendarVersion,
                                                 Instant inspectionEnd, Instant frozenAt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("calendarVersion", calendarVersion);
        evidence.put("inspectionStart", request.windowStart().toString());
        evidence.put("inspectionEnd", inspectionEnd.toString());
        evidence.put("flightWindows", request.flightWindows());
        evidence.put("overlapCheck", "PASS");
        evidence.put("frozenAt", frozenAt.toString());
        return evidence;
    }

    private List<String> mergeItems(String existingJson, List<String> newItems) {
        List<String> merged = new ArrayList<>();
        if (existingJson != null) {
            try {
                merged.addAll(objectMapper.readValue(existingJson, new TypeReference<List<String>>() {
                }));
            } catch (Exception e) {
                throw new IllegalStateException("已完成检查项反序列化失败", e);
            }
        }
        merged.addAll(newItems);
        return merged;
    }

    private PlanResponse toResponse(InspectionPlan plan) {
        return new PlanResponse(plan.id(), plan.runwayId(), plan.status().name(), plan.windowStart(),
                plan.windowEnd(), plan.inspectionMinutes(), plan.calendarVersion(), plan.version(),
                plan.startedAt(), plan.completedItems(), plan.conflictEvidence());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
