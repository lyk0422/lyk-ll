package com.example.starter.runway;

import com.example.starter.runway.dto.CalendarImpactResponse;
import com.example.starter.runway.dto.CalendarPublishRequest;
import com.example.starter.runway.dto.CalendarPublishResponse;
import com.example.starter.runway.dto.CheckItemView;
import com.example.starter.runway.dto.EmergencyEndRequest;
import com.example.starter.runway.dto.EmergencyEndResponse;
import com.example.starter.runway.dto.EmergencyReportRequest;
import com.example.starter.runway.dto.EmergencyReportResponse;
import com.example.starter.runway.dto.EmergencyView;
import com.example.starter.runway.dto.FlightWindowInput;
import com.example.starter.runway.dto.PlanCheckReportRequest;
import com.example.starter.runway.dto.PlanCheckReportResponse;
import com.example.starter.runway.dto.PlanConflictView;
import com.example.starter.runway.dto.PlanPublishRequest;
import com.example.starter.runway.dto.PlanPublishResponse;
import com.example.starter.runway.dto.PlanReviewRequest;
import com.example.starter.runway.dto.PlanStartRequest;
import com.example.starter.runway.dto.PlanStartResponse;
import com.example.starter.runway.dto.RoutinePlanView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 例行巡检窗口编排与紧急关闭抢占服务。
 * 所有写操作在同一事务内完成“跑道行锁→业务校验→版本变更→幂等登记”，
 * 同跑道串行、跨跑道并行；紧急接报同事务取消/中止该跑道存活例行计划。
 */
@Service
public class RoutineWindowService {

    private static final String SCOPE_CALENDAR = "calendar";
    private static final String SCOPE_PLAN_PUBLISH = "plan_publish";
    private static final String SCOPE_PLAN_START = "plan_start";
    private static final String SCOPE_PLAN_CHECK = "plan_check";
    private static final String SCOPE_PLAN_REVIEW = "plan_review";
    private static final String SCOPE_EMERGENCY = "emergency";
    private static final String SCOPE_EMERGENCY_END = "emergency_end";
    /** 日历发布使用的全局互斥键，与具体跑道行锁互不干涉。 */
    private static final String CALENDAR_LOCK_KEY = "__flight_calendar__";

    private final FlightCalendarRepository calendarRepository;
    private final RoutinePlanRepository planRepository;
    private final RoutineCheckItemRepository checkItemRepository;
    private final RunwayEmergencyRepository emergencyRepository;
    private final RunwayMutexRepository mutexRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final FingerprintHasher hasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RoutineWindowService(FlightCalendarRepository calendarRepository,
                                RoutinePlanRepository planRepository,
                                RoutineCheckItemRepository checkItemRepository,
                                RunwayEmergencyRepository emergencyRepository,
                                RunwayMutexRepository mutexRepository,
                                IdempotencyExecutor idempotencyExecutor,
                                FingerprintHasher hasher,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.calendarRepository = calendarRepository;
        this.planRepository = planRepository;
        this.checkItemRepository = checkItemRepository;
        this.emergencyRepository = emergencyRepository;
        this.mutexRepository = mutexRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ===================== 航班日历 =====================

    /**
     * 发布合成航班日历：版本号从1开始递增；同事务将冻结旧版本且未开始的存活计划置为待重审。
     */
    public CalendarPublishResponse publishCalendar(CalendarPublishRequest request) {
        validateFlights(request.flights());
        String fingerprint = hasher.hash(SCOPE_CALENDAR, request.requestId(), request.publishedAt(),
                request.flights().stream().sorted(java.util.Comparator.comparing(FlightWindowInput::flightNo)
                        .thenComparing(FlightWindowInput::runwayId)
                        .thenComparing(FlightWindowInput::startAt)
                        .thenComparing(FlightWindowInput::endAt))
                        .map(f -> List.of(f.flightNo(), f.runwayId(), f.startAt(), f.endAt())).toList());
        try {
            return idempotencyExecutor.execute(SCOPE_CALENDAR, request.requestId(), fingerprint,
                    () -> doPublishCalendar(request), CalendarPublishResponse.class);
        } catch (DuplicateKeyException e) {
            CalendarPublishResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CALENDAR,
                    request.requestId(), fingerprint, CalendarPublishResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("CALENDAR_PUBLICATION_CONFLICT", "航班日历正在并发发布，请重试");
        }
    }

    private CalendarPublishResponse doPublishCalendar(CalendarPublishRequest request) {
        mutexRepository.lockRunway(CALENDAR_LOCK_KEY);
        int newVersion = calendarRepository.findLatestVersion().map(FlightCalendarVersion::version).orElse(0) + 1;
        Instant now = clock.instant();
        calendarRepository.insertVersion(new FlightCalendarVersion(newVersion, request.publishedAt(),
                request.requestId(), now));
        for (FlightWindowInput flight : request.flights()) {
            calendarRepository.insertOccupancy(newVersion, flight.runwayId(), flight.flightNo(),
                    flight.startAt(), flight.endAt());
        }
        List<Long> needsReview = planRepository.findStaleApprovedIds(newVersion);
        planRepository.markStalePlansForReview(newVersion, now);
        return new CalendarPublishResponse(newVersion, request.publishedAt(), request.flights().size(),
                needsReview);
    }

    /**
     * 日历影响查询：按指定版本（缺省最新版本）计算给定巡检区间与航班窗的正长度相交证据。
     */
    @Transactional(readOnly = true)
    public CalendarImpactResponse calendarImpact(String runwayId, Instant windowStart, Instant windowEnd,
                                                 int durationMinutes, Integer calendarVersion) {
        validateWindow(windowStart, windowEnd, durationMinutes);
        int version = resolveLatestCalendarVersion(calendarVersion);
        Instant inspectionStart = windowStart;
        Instant inspectionEnd = windowStart.plus(Duration.ofMinutes(durationMinutes));
        List<PlanConflictView> conflicts = findConflicts(version, runwayId, inspectionStart, inspectionEnd);
        return new CalendarImpactResponse(version, runwayId, windowStart, windowEnd, inspectionStart,
                inspectionEnd, conflicts);
    }

    // ===================== 例行计划发布/开始/重审 =====================

    /**
     * 发布例行计划：冻结最新日历版本与结构化冲突证据；同跑道同窗唯一存活计划。
     */
    public PlanPublishResponse publishPlan(PlanPublishRequest request) {
        validateWindow(request.windowStart(), request.windowEnd(), request.durationMinutes());
        validateCheckItems(request.checkItems());
        String fingerprint = hasher.hash(SCOPE_PLAN_PUBLISH, request.requestId(), request.runwayId(),
                request.windowStart(), request.windowEnd(), request.durationMinutes(),
                request.checkItems().stream().sorted().toList());
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_PUBLISH, request.requestId(), fingerprint,
                    () -> doPublishPlan(request), PlanPublishResponse.class);
        } catch (DuplicateKeyException e) {
            PlanPublishResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_PUBLISH,
                    request.requestId(), fingerprint, PlanPublishResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("uk_routine_plan_live")) {
                throw ApiException.conflict("DUPLICATE_PLAN_WINDOW", "同跑道同窗已存在获准计划");
            }
            throw e;
        }
    }

    private PlanPublishResponse doPublishPlan(PlanPublishRequest request) {
        mutexRepository.lockRunway(request.runwayId());
        int latestVersion = requireLatestCalendarVersion();
        if (planRepository.findLive(request.runwayId(), request.windowStart(), request.windowEnd()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_PLAN_WINDOW", "同跑道同窗已存在获准计划");
        }
        Instant inspectionStart = request.windowStart();
        Instant inspectionEnd = inspectionStart.plus(Duration.ofMinutes(request.durationMinutes()));
        List<FlightOccupancy> allFlights = calendarRepository.findByRunwayAndVersion(latestVersion,
                request.runwayId());
        List<FlightOccupancy> conflicting = calendarRepository.findConflicts(latestVersion, request.runwayId(),
                inspectionStart, inspectionEnd);
        if (!conflicting.isEmpty()) {
            throw new PlanConflictException(latestVersion, toConflictViews(conflicting, inspectionStart,
                    inspectionEnd), "巡检时长与合成航班占用窗正长度相交");
        }
        if (emergencyRepository.findActive(request.runwayId()).isPresent()) {
            throw ApiException.unprocessable("EMERGENCY_ACTIVE", "跑道存在进行中紧急关闭事件，不得发布例行计划");
        }
        Instant now = clock.instant();
        String evidence = writeEvidence(latestVersion, inspectionStart, inspectionEnd, allFlights, List.of());
        RoutinePlan plan = planRepository.insert(new RoutinePlan(0L, request.runwayId(), request.windowStart(),
                request.windowEnd(), request.durationMinutes(), latestVersion, RoutinePlanStatus.APPROVED, 1,
                evidence, 1, request.requestId(), null, null, null, now, now));
        int seq = 1;
        for (String itemCode : request.checkItems().stream().sorted().toList()) {
            checkItemRepository.insert(new RoutineCheckItem(0L, plan.id(), itemCode, seq++, null, false,
                    null, now, now));
        }
        // 发布事务内日历恰好升版：冻结版本已过期，立即转待重审，不能以 APPROVED 落地
        int latestAfter = requireLatestCalendarVersion();
        RoutinePlanStatus finalStatus = RoutinePlanStatus.APPROVED;
        if (latestAfter > latestVersion) {
            planRepository.casUpdate(plan.id(), 1, RoutinePlanStatus.NEEDS_REVIEW, false, null, null, null,
                    null, null, clock.instant());
            finalStatus = RoutinePlanStatus.NEEDS_REVIEW;
        }
        return new PlanPublishResponse(plan.id(), plan.runwayId(), finalStatus.name(),
                finalStatus == RoutinePlanStatus.APPROVED ? 1 : 2, latestVersion, request.windowStart(),
                request.windowEnd(), inspectionStart, inspectionEnd);
    }

    /**
     * 开始巡检：重新确认日历版本（冻结版本且仍为最新）、跑道无进行中紧急关闭，开始时刻使巡检完整落在窗口。
     */
    public PlanStartResponse startPlan(long planId, PlanStartRequest request) {
        String fingerprint = hasher.hash(SCOPE_PLAN_START, request.requestId(), planId,
                request.expectedVersion(), request.calendarVersion(), request.startedAt());
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_START, request.requestId(), fingerprint,
                    () -> doStartPlan(planId, request), PlanStartResponse.class);
        } catch (DuplicateKeyException e) {
            PlanStartResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_START, request.requestId(),
                    fingerprint, PlanStartResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanStartResponse doStartPlan(long planId, PlanStartRequest request) {
        RoutinePlan plan = mustFindPlan(planId);
        mutexRepository.lockRunway(plan.runwayId());
        plan = mustFindPlan(planId);
        requireVersion(plan, request.expectedVersion());
        if (plan.status() == RoutinePlanStatus.NEEDS_REVIEW) {
            throw ApiException.unprocessable("PLAN_NEEDS_REVIEW", "航班日历已变更，计划须重新审核后方可开始");
        }
        if (plan.status() != RoutinePlanStatus.APPROVED) {
            throw ApiException.unprocessable("PLAN_NOT_STARTABLE",
                    "当前计划状态 " + plan.status() + " 不可开始巡检");
        }
        if (emergencyRepository.findActive(plan.runwayId()).isPresent()) {
            throw ApiException.unprocessable("EMERGENCY_ACTIVE", "跑道存在进行中紧急关闭事件，不得开始巡检");
        }
        int latestVersion = requireLatestCalendarVersion();
        if (request.calendarVersion() != plan.frozenCalendarVersion() || request.calendarVersion() != latestVersion) {
            throw ApiException.unprocessable("CALENDAR_VERSION_CHANGED",
                    "开始时日历版本与冻结版本不一致或不是最新版本，计划待重审");
        }
        Instant inspectionEnd = request.startedAt().plus(Duration.ofMinutes(plan.durationMinutes()));
        if (request.startedAt().isBefore(plan.windowStart()) || inspectionEnd.isAfter(plan.windowEnd())) {
            throw ApiException.badRequest("INSPECTION_OUTSIDE_WINDOW", "实际巡检时长须完整落在窗口内");
        }
        List<FlightOccupancy> conflicting = calendarRepository.findConflicts(plan.frozenCalendarVersion(),
                plan.runwayId(), request.startedAt(), inspectionEnd);
        if (!conflicting.isEmpty()) {
            throw new PlanConflictException(plan.frozenCalendarVersion(),
                    toConflictViews(conflicting, request.startedAt(), inspectionEnd),
                    "开始时巡检区间与冻结版本航班窗冲突");
        }
        Instant now = clock.instant();
        int updated = planRepository.casUpdate(planId, request.expectedVersion(),
                RoutinePlanStatus.IN_PROGRESS, false, null, request.startedAt(), null, null, null, now);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "计划版本已变更，请刷新后重试");
        }
        // 日历发布使用独立互斥键：CAS 后在同一事务内复查，若期间日历已升版则整体回滚，计划仍待重审
        Integer latestAfter = calendarRepository.findLatestVersion().map(FlightCalendarVersion::version)
                .orElse(null);
        if (latestAfter == null || latestAfter != request.calendarVersion()) {
            throw ApiException.unprocessable("CALENDAR_VERSION_CHANGED",
                    "开始事务期间航班日历已升版，计划待重审");
        }
        if (emergencyRepository.findActive(plan.runwayId()).isPresent()) {
            throw ApiException.unprocessable("EMERGENCY_ACTIVE", "跑道存在进行中紧急关闭事件，不得开始巡检");
        }
        return new PlanStartResponse(planId, RoutinePlanStatus.IN_PROGRESS.name(),
                request.expectedVersion() + 1, request.startedAt());
    }

    /**
     * 待重审计划重新审核：按指定最新日历版本重算冲突，通过则恢复 APPROVED 并冻结新版本。
     */
    public PlanPublishResponse reviewPlan(long planId, PlanReviewRequest request) {
        String fingerprint = hasher.hash(SCOPE_PLAN_REVIEW, request.requestId(), planId,
                request.expectedVersion(), request.calendarVersion());
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_REVIEW, request.requestId(), fingerprint,
                    () -> doReviewPlan(planId, request), PlanPublishResponse.class);
        } catch (DuplicateKeyException e) {
            PlanPublishResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_REVIEW, request.requestId(),
                    fingerprint, PlanPublishResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanPublishResponse doReviewPlan(long planId, PlanReviewRequest request) {
        RoutinePlan plan = mustFindPlan(planId);
        mutexRepository.lockRunway(plan.runwayId());
        plan = mustFindPlan(planId);
        requireVersion(plan, request.expectedVersion());
        if (plan.status() != RoutinePlanStatus.NEEDS_REVIEW) {
            throw ApiException.unprocessable("PLAN_NOT_NEEDS_REVIEW",
                    "仅待重审计划可重新审核，当前状态 " + plan.status());
        }
        int latestVersion = requireLatestCalendarVersion();
        if (request.calendarVersion() != latestVersion) {
            throw ApiException.unprocessable("CALENDAR_VERSION_NOT_LATEST",
                    "重审须依据当前最新日历版本 " + latestVersion);
        }
        Instant inspectionStart = plan.windowStart();
        Instant inspectionEnd = inspectionStart.plus(Duration.ofMinutes(plan.durationMinutes()));
        List<FlightOccupancy> allFlights = calendarRepository.findByRunwayAndVersion(latestVersion,
                plan.runwayId());
        List<FlightOccupancy> conflicting = calendarRepository.findConflicts(latestVersion, plan.runwayId(),
                inspectionStart, inspectionEnd);
        if (!conflicting.isEmpty()) {
            throw new PlanConflictException(latestVersion, toConflictViews(conflicting, inspectionStart,
                    inspectionEnd), "重审版本下巡检时长仍与航班占用窗正长度相交");
        }
        String evidence = writeEvidence(latestVersion, inspectionStart, inspectionEnd, allFlights, List.of());
        int updated = planRepository.casUpdate(planId, request.expectedVersion(), RoutinePlanStatus.APPROVED,
                false, latestVersion, null, null, null, evidence, clock.instant());
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "计划版本已变更，请刷新后重试");
        }
        return new PlanPublishResponse(planId, plan.runwayId(), RoutinePlanStatus.APPROVED.name(),
                request.expectedVersion() + 1, latestVersion, plan.windowStart(), plan.windowEnd(),
                inspectionStart, inspectionEnd);
    }

    // ===================== 检查回报 =====================

    /**
     * 检查项回报：进行中计划登记完成并在全部完成时正常关闭计划（不触发恢复开放/通告）；
     * 已中止计划的新回报仅登记迟到，计划维持 ABORTED，不得开放跑道。
     */
    public PlanCheckReportResponse reportCheck(long planId, PlanCheckReportRequest request) {
        String fingerprint = hasher.hash(SCOPE_PLAN_CHECK, request.requestId(), planId,
                request.expectedVersion(), request.itemCode(), request.completedAt());
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN_CHECK, request.requestId(), fingerprint,
                    () -> doReportCheck(planId, request), PlanCheckReportResponse.class);
        } catch (DuplicateKeyException e) {
            PlanCheckReportResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_CHECK,
                    request.requestId(), fingerprint, PlanCheckReportResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("uk_routine_check_item")) {
                throw ApiException.conflict("DUPLICATE_CHECK_ITEM", "检查项编码在该计划内重复上报");
            }
            throw e;
        }
    }

    private PlanCheckReportResponse doReportCheck(long planId, PlanCheckReportRequest request) {
        RoutinePlan plan = mustFindPlan(planId);
        mutexRepository.lockRunway(plan.runwayId());
        plan = mustFindPlan(planId);
        RoutineCheckItem item = checkItemRepository.findByPlanAndCode(planId, request.itemCode())
                .orElseThrow(() -> ApiException.notFound("CHECK_ITEM_NOT_FOUND",
                        "检查项不存在: " + request.itemCode()));
        if (item.completedAt() != null) {
            throw ApiException.unprocessable("CHECK_ITEM_ALREADY_REPORTED", "该检查项已完成回报");
        }
        boolean late;
        if (plan.status() == RoutinePlanStatus.ABORTED) {
            // 中止后的迟到回报是只追加证据：不校验客户端版本，也不改变计划版本与状态
            late = true;
        } else if (plan.status() == RoutinePlanStatus.COMPLETED) {
            throw ApiException.unprocessable("PLAN_ALREADY_COMPLETED", "计划已正常完成");
        } else if (plan.status() == RoutinePlanStatus.CANCELLED) {
            throw ApiException.unprocessable("PLAN_CANCELLED", "计划已被紧急关闭取消，不再受理回报");
        } else if (plan.status() != RoutinePlanStatus.IN_PROGRESS) {
            throw ApiException.unprocessable("PLAN_NOT_STARTED", "计划尚未开始，不受理检查回报");
        } else {
            requireVersion(plan, request.expectedVersion());
            if (request.completedAt().isBefore(plan.startedAt())) {
                throw ApiException.badRequest("CHECK_COMPLETED_BEFORE_START", "检查项完成时刻不得早于巡检开始时刻");
            }
            late = false;
        }
        Instant now = clock.instant();
        int marked = checkItemRepository.markCompleted(item.id(), request.completedAt(), late,
                request.requestId(), now);
        if (marked == 0) {
            throw ApiException.unprocessable("CHECK_ITEM_ALREADY_REPORTED", "该检查项已完成回报");
        }
        boolean planCompleted = false;
        if (!late) {
            List<RoutineCheckItem> items = checkItemRepository.findByPlanId(planId);
            boolean allDone = items.stream().allMatch(i -> i.id() == item.id() || i.completedAt() != null);
            if (allDone) {
                int updated = planRepository.casUpdate(planId, request.expectedVersion(),
                        RoutinePlanStatus.COMPLETED, true, null, null, request.completedAt(), null, null, now);
                if (updated == 0) {
                    throw ApiException.conflict("VERSION_CONFLICT", "计划版本已变更，请刷新后重试");
                }
                planCompleted = true;
            } else {
                // 每次成功的检查回报均使计划版本加1，状态仍为 IN_PROGRESS
                int updated = planRepository.casUpdate(planId, request.expectedVersion(),
                        RoutinePlanStatus.IN_PROGRESS, false, null, null, null, null, null, now);
                if (updated == 0) {
                    throw ApiException.conflict("VERSION_CONFLICT", "计划版本已变更，请刷新后重试");
                }
            }
        }
        List<RoutineCheckItem> itemsAfter = checkItemRepository.findByPlanId(planId);
        int doneCount = (int) itemsAfter.stream().filter(i -> i.completedAt() != null).count();
        RoutinePlan planAfter = mustFindPlan(planId);
        return new PlanCheckReportResponse(planId, request.itemCode(), late, planAfter.status().name(),
                planAfter.version(), doneCount, itemsAfter.size(), planCompleted, request.completedAt());
    }

    // ===================== 紧急关闭抢占 =====================

    /**
     * 接报紧急关闭：同事务插入进行中紧急事件并取消该跑道未开始计划、中止进行中计划（保留已完成检查项）。
     */
    public EmergencyReportResponse reportEmergency(EmergencyReportRequest request) {
        String fingerprint = hasher.hash(SCOPE_EMERGENCY, request.requestId(), request.runwayId(),
                request.reason(), request.startedAt());
        try {
            return idempotencyExecutor.execute(SCOPE_EMERGENCY, request.requestId(), fingerprint,
                    () -> doReportEmergency(request), EmergencyReportResponse.class);
        } catch (DuplicateKeyException e) {
            EmergencyReportResponse replayed = idempotencyExecutor.tryReplay(SCOPE_EMERGENCY,
                    request.requestId(), fingerprint, EmergencyReportResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("uk_runway_emergency_active")) {
                throw ApiException.conflict("EMERGENCY_ALREADY_ACTIVE", "同一跑道已存在进行中紧急关闭事件");
            }
            throw e;
        }
    }

    private EmergencyReportResponse doReportEmergency(EmergencyReportRequest request) {
        mutexRepository.lockRunway(request.runwayId());
        if (emergencyRepository.findActive(request.runwayId()).isPresent()) {
            throw ApiException.conflict("EMERGENCY_ALREADY_ACTIVE", "同一跑道已存在进行中紧急关闭事件");
        }
        Instant now = clock.instant();
        RunwayEmergency emergency = emergencyRepository.insert(new RunwayEmergency(0L, request.runwayId(),
                request.reason(), EmergencyStatus.ACTIVE, 1, request.startedAt(), null, 1,
                request.requestId(), now, now));
        List<RoutinePlan> livePlans = planRepository.findLiveByRunway(request.runwayId());
        List<Long> cancelled = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        for (RoutinePlan p : livePlans) {
            if (p.status() == RoutinePlanStatus.IN_PROGRESS) {
                aborted.add(p.id());
            } else {
                cancelled.add(p.id());
            }
        }
        planRepository.cancelLiveNotStarted(request.runwayId(), request.startedAt());
        planRepository.abortInProgress(request.runwayId(), request.startedAt());
        return new EmergencyReportResponse(emergency.id(), request.runwayId(), EmergencyStatus.ACTIVE.name(),
                1, request.startedAt(), cancelled, aborted);
    }

    /**
     * 结束紧急事件：之后可另建新例行计划，被抢占计划不复活。
     */
    public EmergencyEndResponse endEmergency(long emergencyId, EmergencyEndRequest request) {
        String fingerprint = hasher.hash(SCOPE_EMERGENCY_END, request.requestId(), emergencyId,
                request.expectedVersion(), request.endedAt());
        try {
            return idempotencyExecutor.execute(SCOPE_EMERGENCY_END, request.requestId(), fingerprint,
                    () -> doEndEmergency(emergencyId, request), EmergencyEndResponse.class);
        } catch (DuplicateKeyException e) {
            EmergencyEndResponse replayed = idempotencyExecutor.tryReplay(SCOPE_EMERGENCY_END,
                    request.requestId(), fingerprint, EmergencyEndResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private EmergencyEndResponse doEndEmergency(long emergencyId, EmergencyEndRequest request) {
        RunwayEmergency emergency = emergencyRepository.findById(emergencyId)
                .orElseThrow(() -> ApiException.notFound("EMERGENCY_NOT_FOUND",
                        "紧急事件不存在: " + emergencyId));
        mutexRepository.lockRunway(emergency.runwayId());
        if (emergency.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "紧急事件版本不匹配，请刷新后重试");
        }
        if (emergency.status() != EmergencyStatus.ACTIVE) {
            throw ApiException.unprocessable("EMERGENCY_NOT_ACTIVE", "紧急事件已结束，不得重复结束");
        }
        if (request.endedAt().isBefore(emergency.startedAt())) {
            throw ApiException.badRequest("EMERGENCY_END_BEFORE_START", "结束时刻不得早于紧急关闭生效时刻");
        }
        int updated = emergencyRepository.casEnd(emergencyId, request.expectedVersion(), request.endedAt(),
                clock.instant());
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "紧急事件版本已变更，请刷新后重试");
        }
        return new EmergencyEndResponse(emergencyId, emergency.runwayId(), EmergencyStatus.ENDED.name(),
                request.expectedVersion() + 1, request.endedAt());
    }

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public RoutinePlanView getPlan(long planId) {
        RoutinePlan plan = mustFindPlan(planId);
        FrozenEvidence evidence = readEvidence(plan.conflictEvidence());
        int latest = calendarRepository.findLatestVersion().map(FlightCalendarVersion::version).orElse(0);
        Instant inspectionStart = plan.windowStart();
        Instant inspectionEnd = inspectionStart.plus(Duration.ofMinutes(plan.durationMinutes()));
        List<CheckItemView> itemViews = checkItemRepository.findByPlanId(planId).stream()
                .map(i -> new CheckItemView(i.itemCode(), i.seqNo(), i.completedAt(), i.recordedLate()))
                .toList();
        List<RoutinePlanView.FrozenFlightView> flightViews = evidence.flights().stream()
                .map(f -> new RoutinePlanView.FrozenFlightView(f.flightNo(), f.startAt(), f.endAt()))
                .toList();
        return new RoutinePlanView(plan.id(), plan.runwayId(), plan.windowStart(), plan.windowEnd(),
                plan.durationMinutes(), inspectionStart, inspectionEnd, plan.frozenCalendarVersion(), latest,
                plan.status().name(), plan.version(), plan.startedAt(), plan.completedAt(), plan.preemptedAt(),
                flightViews, evidence.conflicts(), itemViews);
    }

    @Transactional(readOnly = true)
    public List<RoutinePlanView> listPlans(String runwayId) {
        return planRepository.findByRunway(runwayId).stream().map(p -> getPlan(p.id())).toList();
    }

    @Transactional(readOnly = true)
    public EmergencyView getEmergency(long emergencyId) {
        RunwayEmergency emergency = emergencyRepository.findById(emergencyId)
                .orElseThrow(() -> ApiException.notFound("EMERGENCY_NOT_FOUND",
                        "紧急事件不存在: " + emergencyId));
        return new EmergencyView(emergency.id(), emergency.runwayId(), emergency.reason(),
                emergency.status().name(), emergency.version(), emergency.startedAt(), emergency.endedAt());
    }

    // ===================== 校验与证据 =====================

    private RoutinePlan mustFindPlan(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "例行计划不存在: " + planId));
    }

    private void requireVersion(RoutinePlan plan, int expectedVersion) {
        if (plan.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不匹配：当前版本 " + plan.version() + "，期望版本 " + expectedVersion);
        }
    }

    private int requireLatestCalendarVersion() {
        return calendarRepository.findLatestVersion().map(FlightCalendarVersion::version)
                .orElseThrow(() -> ApiException.badRequest("CALENDAR_NOT_FOUND",
                        "尚未发布合成航班日历，无法冻结日历版本"));
    }

    private int resolveLatestCalendarVersion(Integer calendarVersion) {
        if (calendarVersion != null) {
            return calendarRepository.findVersion(calendarVersion).map(FlightCalendarVersion::version)
                    .orElseThrow(() -> ApiException.notFound("CALENDAR_VERSION_NOT_FOUND",
                            "航班日历版本不存在: " + calendarVersion));
        }
        return requireLatestCalendarVersion();
    }

    private void validateWindow(Instant windowStart, Instant windowEnd, int durationMinutes) {
        if (windowStart == null || windowEnd == null) {
            throw ApiException.badRequest("INVALID_WINDOW", "窗口起止时刻不能为空");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw ApiException.badRequest("INVALID_WINDOW", "窗口结束时刻必须晚于开始时刻（UTC 半开区间）");
        }
        if (durationMinutes <= 0) {
            throw ApiException.badRequest("INVALID_DURATION", "预计巡检分钟数必须为正整数");
        }
        if (windowStart.plus(Duration.ofMinutes(durationMinutes)).isAfter(windowEnd)) {
            throw ApiException.badRequest("INSPECTION_OUTSIDE_WINDOW",
                    "巡检时长须完整落在窗口内（端点相接合法）");
        }
    }

    private void validateFlights(List<FlightWindowInput> flights) {
        for (FlightWindowInput flight : flights) {
            if (!flight.endAt().isAfter(flight.startAt())) {
                throw ApiException.badRequest("INVALID_FLIGHT_WINDOW",
                        "航班占用窗结束时刻必须晚于开始时刻: " + flight.flightNo());
            }
        }
    }

    private void validateCheckItems(List<String> checkItems) {
        long distinct = checkItems.stream().distinct().count();
        if (distinct != checkItems.size()) {
            throw ApiException.badRequest("DUPLICATE_CHECK_ITEMS", "检查项编码集合不可重复");
        }
    }

    private List<PlanConflictView> findConflicts(int version, String runwayId, Instant from, Instant to) {
        return toConflictViews(calendarRepository.findConflicts(version, runwayId, from, to), from, to);
    }

    private List<PlanConflictView> toConflictViews(List<FlightOccupancy> conflicting, Instant from, Instant to) {
        List<PlanConflictView> views = new ArrayList<>();
        for (FlightOccupancy f : conflicting) {
            Instant overlapStart = f.startAt().isAfter(from) ? f.startAt() : from;
            Instant overlapEnd = f.endAt().isBefore(to) ? f.endAt() : to;
            long overlapMinutes = Duration.between(overlapStart, overlapEnd).toMinutes();
            views.add(new PlanConflictView(f.flightNo(), f.startAt(), f.endAt(), overlapMinutes));
        }
        return views;
    }

    private String writeEvidence(int calendarVersion, Instant inspectionStart, Instant inspectionEnd,
                                 List<FlightOccupancy> allFlights, List<PlanConflictView> conflicts) {
        List<EvidenceFlight> flights = allFlights.stream()
                .map(f -> new EvidenceFlight(f.flightNo(), f.startAt(), f.endAt()))
                .toList();
        FrozenEvidence evidence = new FrozenEvidence(calendarVersion, inspectionStart, inspectionEnd, flights,
                conflicts);
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("冲突证据序列化失败", e);
        }
    }

    private FrozenEvidence readEvidence(String json) {
        try {
            return objectMapper.readValue(json, FrozenEvidence.class);
        } catch (Exception e) {
            throw new IllegalStateException("冲突证据反序列化失败", e);
        }
    }

    /**
     * 冻结证据持久化结构：日历版本、巡检区间、该跑道该版本全部受检航班窗与冲突行。
     */
    private record FrozenEvidence(int calendarVersion, Instant inspectionStart, Instant inspectionEnd,
                                  List<EvidenceFlight> flights, List<PlanConflictView> conflicts) {
    }

    private record EvidenceFlight(String flightNo, Instant startAt, Instant endAt) {
    }
}
