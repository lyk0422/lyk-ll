package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 例行跑道巡检计划与航班日历服务。
 * 规则要点：
 * 1) 日历版本从1开始，每次成功发布加1；发布即冻结占用窗快照，并把尚未开始（FROZEN）计划置待重审，不影响已开始巡检；
 * 2) 计划发布冻结日历版本与结构化冲突证据，巡检区间须完整落在窗口、与航班占用窗仅端点相接；同跑道同窗唯一获准计划；
 * 3) 开始须重新确认日历版本且跑道无进行中紧急关闭；跑道级行锁串行化与紧急接报的竞争，跨跑道互不阻塞；
 * 4) 紧急接报同事务取消未开始计划、中止已开始计划并保留检查项、释放巡检占用；中止后的完成回报只登记迟到，不开放跑道；
 * 5) 正常完成只关闭计划，不触发恢复开放/通告；紧急结束后只能另建新计划，不复活被抢占项。
 * 业务写入、计划版本变更与幂等记录在同一事务内完成。
 */
@Service
public class RoutinePlanService {

    static final String SCOPE_CALENDAR = "calendar";
    static final String SCOPE_PLAN_PUBLISH = "plan-publish";
    static final String SCOPE_PLAN_START = "plan-start";
    static final String SCOPE_PLAN_CHECK = "plan-check";
    static final String SCOPE_PLAN_COMPLETE = "plan-complete";
    static final String SCOPE_PLAN_REVIEW = "plan-rereview";

    private final FlightCalendarRepository calendarRepository;
    private final FlightWindowRepository flightWindowRepository;
    private final InspectionPlanRepository planRepository;
    private final PlanCheckItemRepository checkItemRepository;
    private final PlanCompletionRepository completionRepository;
    private final RunwayCoordinationRepository coordinationRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RoutinePlanService(FlightCalendarRepository calendarRepository,
                              FlightWindowRepository flightWindowRepository,
                              InspectionPlanRepository planRepository,
                              PlanCheckItemRepository checkItemRepository,
                              PlanCompletionRepository completionRepository,
                              RunwayCoordinationRepository coordinationRepository,
                              IdempotencyExecutor idempotencyExecutor,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.calendarRepository = calendarRepository;
        this.flightWindowRepository = flightWindowRepository;
        this.planRepository = planRepository;
        this.checkItemRepository = checkItemRepository;
        this.completionRepository = completionRepository;
        this.coordinationRepository = coordinationRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================== 航班日历 ==============================

    /**
     * 发布新航班日历版本：expectedVersion=0 为首次发布（结果版本1），之后须等于当前版本。
     * 同事务冻结占用窗快照，并把所有尚未开始（FROZEN）计划置为待重审。
     */
    public CalendarPublishResponse publishCalendar(CalendarPublishRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_CALENDAR, buildCalendarTokens(request));
        try {
            return executeIdempotent(SCOPE_CALENDAR, request.requestId(), fingerprint,
                    () -> doPublishCalendar(request), CalendarPublishResponse.class);
        } catch (DuplicateKeyException e) {
            CalendarPublishResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CALENDAR,
                    request.requestId(), fingerprint, CalendarPublishResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isConstraint(e, "pk_flight_calendar") || isConstraint(e, "primary")) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT", "日历版本已被并发发布，请基于最新版本重试");
            }
            throw e;
        }
    }

    private CalendarPublishResponse doPublishCalendar(CalendarPublishRequest request) {
        for (FlightOccupancyInput window : request.windows()) {
            if (!window.occupyEnd().isAfter(window.occupyStart())) {
                throw ApiException.badRequest("INVALID_OCCUPANCY_WINDOW",
                        "航班占用窗结束时刻必须严格晚于开始时刻: " + window.flightNo());
            }
        }
        Instant now = clock.instant();
        int newVersion;
        var current = calendarRepository.findCurrent();
        if (request.expectedVersion() == 0) {
            if (current.isPresent()) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                        "日历已存在（当前版本" + current.get().version() + "），首次发布 expectedVersion 应为0仅在日历不存在时使用");
            }
            try {
                calendarRepository.insertInitial(request.publishedAt(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                        "日历版本已被并发发布，请基于最新版本重试");
            }
            newVersion = 1;
        } else {
            if (current.isEmpty()) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT", "日历尚不存在，请先以 expectedVersion=0 首次发布");
            }
            if (current.get().version() != request.expectedVersion()) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                        "日历版本已变更：期望" + request.expectedVersion() + "，当前" + current.get().version());
            }
            int updated = calendarRepository.advanceVersion(request.expectedVersion(), request.publishedAt(), now);
            if (updated == 0) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT", "日历版本已被并发发布，请基于最新版本重试");
            }
            newVersion = request.expectedVersion() + 1;
        }

        flightWindowRepository.insertBatch(newVersion, request.windows(), now);

        // 尚未开始（FROZEN）计划随日历变更置待重审并释放名额；已开始（RUNNING）及终态计划不动，不自动平移
        List<InspectionPlanRecord> frozen = planRepository.findByStatus(PlanStatus.FROZEN);
        List<Long> impactedPlanIds = new ArrayList<>();
        for (InspectionPlanRecord plan : frozen) {
            int rows = planRepository.compareAndUpdate(plan, PlanStatus.REVIEW_PENDING,
                    plan.frozenCalendarVersion(), plan.conflictEvidence(), null, null, now);
            if (rows == 1) {
                impactedPlanIds.add(plan.id());
            }
        }
        return new CalendarPublishResponse(newVersion, request.publishedAt(), request.windows().size(),
                impactedPlanIds);
    }

    /**
     * 日历影响查询：当前版本下冻结版本落后且尚未开始（待重审）的计划。
     */
    @Transactional(readOnly = true)
    public CalendarImpactResponse getCalendarImpact() {
        FlightCalendarRecord current = calendarRepository.findCurrent()
                .orElseThrow(() -> ApiException.notFound("CALENDAR_NOT_FOUND", "航班日历尚未发布"));
        List<CalendarImpactItem> items = new ArrayList<>();
        for (InspectionPlanRecord plan : planRepository.findByStatus(PlanStatus.REVIEW_PENDING)) {
            items.add(new CalendarImpactItem(plan.id(), plan.runwayId(),
                    nullSafe(plan.frozenCalendarVersion()), current.version(), plan.status().name()));
        }
        return new CalendarImpactResponse(current.version(), current.publishedAt(), items);
    }

    // ============================== 例行计划发布 ==============================

    /**
     * 例行计划申请发布：窗口完整包含巡检区间、当前日历版本下无正长度航班相交、同跑道同窗无其他获准计划。
     */
    public PlanResponse publishPlan(PlanPublishRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_PLAN_PUBLISH,
                request.requestId(), request.runwayId(),
                request.windowStart().toString(), request.windowEnd().toString(),
                request.plannedStartAt().toString(), String.valueOf(request.durationMinutes()),
                String.valueOf(request.expectedVersion()));
        try {
            return executeIdempotent(SCOPE_PLAN_PUBLISH, request.requestId(), fingerprint,
                    () -> doPublishPlan(request), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_PUBLISH, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isConstraint(e, "uk_inspection_plan_window")) {
                throw ApiException.unprocessable("PLAN_WINDOW_OCCUPIED", "同跑道同窗已存在一个获准例行计划");
            }
            throw e;
        }
    }

    private PlanResponse doPublishPlan(PlanPublishRequest request) {
        coordinationRepository.lockRunway(request.runwayId());
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw ApiException.badRequest("INVALID_PLAN_WINDOW", "例行窗口结束时刻必须严格晚于开始时刻");
        }
        Instant inspectEnd = request.plannedStartAt()
                .plusSeconds(request.durationMinutes() * 60L);
        if (request.plannedStartAt().isBefore(request.windowStart()) || inspectEnd.isAfter(request.windowEnd())) {
            throw ApiException.unprocessable("INSPECTION_NOT_WITHIN_WINDOW",
                    "巡检区间须完整落在窗口内（含端点相接合法，超出窗口拒绝）");
        }
        FlightCalendarRecord calendar = requireCalendarVersion(request.expectedVersion());
        List<FlightWindowRecord> flightWindows =
                flightWindowRepository.findByVersionAndRunway(calendar.version(), request.runwayId());
        List<String> conflicts = overlappingFlights(flightWindows, request.plannedStartAt(), inspectEnd);
        if (!conflicts.isEmpty()) {
            throw ApiException.unprocessable("FLIGHT_OCCUPANCY_CONFLICT",
                    "巡检区间与航班占用窗正长度相交: " + String.join(",", conflicts));
        }
        Instant now = clock.instant();
        String evidence = writeEvidence(buildEvidence(calendar.version(), request.runwayId(),
                request.windowStart(), request.windowEnd(), request.plannedStartAt(), inspectEnd,
                flightWindows, conflicts));
        InspectionPlanRecord plan = new InspectionPlanRecord(0L, request.runwayId(), request.windowStart(),
                request.windowEnd(), request.plannedStartAt(), request.durationMinutes(), PlanStatus.FROZEN,
                1, calendar.version(), evidence, 1, null, now, now);
        InspectionPlanRecord saved;
        try {
            saved = planRepository.insert(plan);
        } catch (DuplicateKeyException e) {
            if (isConstraint(e, "uk_inspection_plan_window")) {
                throw ApiException.unprocessable("PLAN_WINDOW_OCCUPIED", "同跑道同窗已存在一个获准例行计划");
            }
            throw e;
        }
        return toPlanResponse(saved);
    }

    // ============================== 巡检开始 ==============================

    /**
     * 例行巡检开始：持有跑道行锁后重新确认日历版本与进行中紧急事件，占用跑道并转 RUNNING。
     */
    public PlanStartResponse startPlan(long planId, PlanStartRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_PLAN_START,
                String.valueOf(planId), request.requestId(), String.valueOf(request.expectedVersion()),
                request.startedAt().toString());
        try {
            return executeIdempotent(SCOPE_PLAN_START, request.requestId(), fingerprint,
                    () -> doStartPlan(planId, request), PlanStartResponse.class);
        } catch (DuplicateKeyException e) {
            PlanStartResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_START, request.requestId(),
                    fingerprint, PlanStartResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isConstraint(e, "primary") || isConstraint(e, "runway_inspection_occupancy")) {
                throw ApiException.unprocessable("RUNWAY_INSPECTION_OCCUPIED",
                        "该跑道已有进行中例行巡检，唯一占用状态冲突");
            }
            throw e;
        }
    }

    private PlanStartResponse doStartPlan(long planId, PlanStartRequest request) {
        InspectionPlanRecord initial = mustFindPlan(planId);
        coordinationRepository.lockRunway(initial.runwayId());
        // 拿锁后重读：计划可能已被持锁的紧急接报并发取消，必须以锁内最新状态裁决
        InspectionPlanRecord plan = mustFindPlan(planId);
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更：期望" + request.expectedVersion() + "，当前" + plan.version());
        }
        if (plan.status() == PlanStatus.RUNNING) {
            throw ApiException.conflict("PLAN_ALREADY_STARTED", "计划已开始巡检");
        }
        if (plan.status() == PlanStatus.REVIEW_PENDING) {
            throw ApiException.unprocessable("PLAN_REVIEW_REQUIRED", "日历版本已变更，计划须重新审查冻结后方可开始");
        }
        if (plan.status() != PlanStatus.FROZEN) {
            throw ApiException.unprocessable("PLAN_NOT_STARTABLE", "计划处于终态" + plan.status() + "，不能开始");
        }
        FlightCalendarRecord calendar = calendarRepository.findCurrent()
                .orElseThrow(() -> ApiException.conflict("CALENDAR_VERSION_CONFLICT", "航班日历不存在，无法开始"));
        if (plan.frozenCalendarVersion() == null || plan.frozenCalendarVersion() != calendar.version()) {
            throw ApiException.unprocessable("PLAN_CALENDAR_STALE",
                    "开始时日历版本已变化（冻结" + plan.frozenCalendarVersion() + "，当前" + calendar.version()
                            + "），须重新审查，不得按旧版本开始");
        }
        if (coordinationRepository.hasActiveEmergency(plan.runwayId())) {
            throw ApiException.unprocessable("RUNWAY_EMERGENCY_ACTIVE",
                    "跑道存在进行中紧急关闭事件，不得开始例行巡检");
        }
        Instant now = clock.instant();
        int rows = planRepository.compareAndUpdate(plan, PlanStatus.RUNNING, plan.frozenCalendarVersion(),
                plan.conflictEvidence(), 1, null, now);
        if (rows == 0) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT", "计划已被并发变更，请刷新版本后重试");
        }
        try {
            coordinationRepository.occupy(plan.runwayId(), planId, request.startedAt());
        } catch (DuplicateKeyException e) {
            // 唯一占用冲突：整个事务回滚（含状态变更），由唯一索引保证不产生双进行中
            throw ApiException.unprocessable("RUNWAY_INSPECTION_OCCUPIED",
                    "该跑道已有进行中例行巡检，唯一占用状态冲突");
        }
        return new PlanStartResponse(planId, PlanStatus.RUNNING.name(), plan.version() + 1,
                request.startedAt(), calendar.version());
    }

    // ============================== 检查项回报 ==============================

    /**
     * 检查项回报：仅 RUNNING 受理并使计划版本加1；中止后的检查回报拒绝登记（完成回报才有迟到语义）。
     */
    public PlanCheckResponse reportCheck(long planId, PlanCheckItemRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_PLAN_CHECK,
                String.valueOf(planId), request.requestId(), String.valueOf(request.expectedVersion()),
                request.itemCode(), request.result().name(),
                request.remark() == null ? "" : request.remark(), request.observedAt().toString());
        return executeIdempotent(SCOPE_PLAN_CHECK, request.requestId(), fingerprint,
                () -> doReportCheck(planId, request), PlanCheckResponse.class);
    }

    private PlanCheckResponse doReportCheck(long planId, PlanCheckItemRequest request) {
        InspectionPlanRecord plan = mustFindPlan(planId);
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更：期望" + request.expectedVersion() + "，当前" + plan.version());
        }
        if (plan.status() != PlanStatus.RUNNING) {
            throw ApiException.unprocessable("PLAN_NOT_RUNNING",
                    "仅巡检中计划受理检查回报，当前状态" + plan.status());
        }
        PlanCheckItemRecord saved = checkItemRepository.insert(new PlanCheckItemRecord(0L, planId,
                request.itemCode(), request.result(), request.remark(), request.observedAt(),
                request.requestId(), clock.instant()));
        Instant now = clock.instant();
        int rows = planRepository.compareAndUpdate(plan, PlanStatus.RUNNING, plan.frozenCalendarVersion(),
                plan.conflictEvidence(), 1, null, now);
        if (rows == 0) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT", "计划已被并发变更，请刷新版本后重试");
        }
        return new PlanCheckResponse(saved.id(), planId, PlanStatus.RUNNING.name(), plan.version() + 1,
                saved.itemCode(), saved.result().name(), saved.observedAt());
    }

    // ============================== 完成回报 ==============================

    /**
     * 完成回报：RUNNING 正常完成（关闭计划并释放占用，版本加1，不开放跑道）；
     * ABORTED 后仅登记迟到（版本/状态不变，不开放跑道）。
     */
    public PlanCompleteResponse completePlan(long planId, PlanCompleteRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_PLAN_COMPLETE,
                String.valueOf(planId), request.requestId(), String.valueOf(request.expectedVersion()),
                request.completedAt().toString());
        return executeIdempotent(SCOPE_PLAN_COMPLETE, request.requestId(), fingerprint,
                () -> doCompletePlan(planId, request), PlanCompleteResponse.class);
    }

    private PlanCompleteResponse doCompletePlan(long planId, PlanCompleteRequest request) {
        InspectionPlanRecord initial = mustFindPlan(planId);
        coordinationRepository.lockRunway(initial.runwayId());
        // 拿锁后重读：可能恰被持锁的紧急接报中止
        InspectionPlanRecord plan = mustFindPlan(planId);
        Instant now = clock.instant();

        if (plan.status() == PlanStatus.ABORTED) {
            // 迟到完成：只登记，不改变状态/版本，不开放跑道
            completionRepository.insert(new PlanCompletionRecord(0L, planId, request.completedAt(), true,
                    request.requestId(), now));
            return new PlanCompleteResponse(planId, PlanStatus.ABORTED.name(), plan.version(), true,
                    request.completedAt());
        }
        if (plan.status() != PlanStatus.RUNNING) {
            if (plan.status() == PlanStatus.COMPLETED) {
                throw ApiException.unprocessable("PLAN_ALREADY_COMPLETED", "计划已正常完成，重复完成须使用同一 requestId 重放");
            }
            if (plan.status() == PlanStatus.CANCELLED) {
                throw ApiException.unprocessable("PLAN_NEVER_STARTED", "计划在开始前已被紧急取消，不存在完成回报");
            }
            throw ApiException.unprocessable("PLAN_NOT_RUNNING", "仅巡检中计划可正常完成，当前状态" + plan.status());
        }
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更：期望" + request.expectedVersion() + "，当前" + plan.version());
        }
        int rows = planRepository.compareAndUpdate(plan, PlanStatus.COMPLETED, plan.frozenCalendarVersion(),
                plan.conflictEvidence(), null, null, now);
        if (rows == 0) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT", "计划已被并发变更，请刷新版本后重试");
        }
        completionRepository.insert(new PlanCompletionRecord(0L, planId, request.completedAt(), false,
                request.requestId(), now));
        coordinationRepository.release(plan.runwayId());
        return new PlanCompleteResponse(planId, PlanStatus.COMPLETED.name(), plan.version() + 1, false,
                request.completedAt());
    }

    // ============================== 重新审查 ==============================

    /**
     * 待重审计划按当前日历版本重新校验并冻结；仍冲突则保持待重审（422）。
     */
    public PlanResponse reviewPlan(long planId, PlanReviewRequest request) {
        String fingerprint = Fingerprint.of(SCOPE_PLAN_REVIEW,
                String.valueOf(planId), request.requestId(), String.valueOf(request.expectedVersion()),
                String.valueOf(request.calendarVersion()));
        try {
            return executeIdempotent(SCOPE_PLAN_REVIEW, request.requestId(), fingerprint,
                    () -> doReviewPlan(planId, request), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN_REVIEW, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isConstraint(e, "uk_inspection_plan_window")) {
                throw ApiException.unprocessable("PLAN_WINDOW_OCCUPIED", "同跑道同窗已存在另一个获准例行计划");
            }
            throw e;
        }
    }

    private PlanResponse doReviewPlan(long planId, PlanReviewRequest request) {
        InspectionPlanRecord plan = mustFindPlan(planId);
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更：期望" + request.expectedVersion() + "，当前" + plan.version());
        }
        if (plan.status() != PlanStatus.REVIEW_PENDING) {
            throw ApiException.unprocessable("PLAN_NOT_REVIEW_PENDING", "仅待重审计划可执行重新审查，当前状态"
                    + plan.status());
        }
        FlightCalendarRecord calendar = requireCalendarVersion(request.calendarVersion());
        Instant inspectEnd = plan.plannedEndAt();
        List<FlightWindowRecord> flightWindows =
                flightWindowRepository.findByVersionAndRunway(calendar.version(), plan.runwayId());
        List<String> conflicts = overlappingFlights(flightWindows, plan.plannedStartAt(), inspectEnd);
        if (!conflicts.isEmpty()) {
            throw ApiException.unprocessable("FLIGHT_OCCUPANCY_CONFLICT",
                    "当前日历版本下巡检区间仍与航班占用窗正长度相交: " + String.join(",", conflicts));
        }
        String evidence = writeEvidence(buildEvidence(calendar.version(), plan.runwayId(), plan.windowStart(),
                plan.windowEnd(), plan.plannedStartAt(), inspectEnd, flightWindows, conflicts));
        Instant now = clock.instant();
        int rows;
        try {
            rows = planRepository.compareAndUpdate(plan, PlanStatus.FROZEN, calendar.version(), evidence, 1,
                    null, now);
        } catch (DuplicateKeyException e) {
            if (isConstraint(e, "uk_inspection_plan_window")) {
                throw ApiException.unprocessable("PLAN_WINDOW_OCCUPIED", "同跑道同窗已存在另一个获准例行计划");
            }
            throw e;
        }
        if (rows == 0) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT", "计划已被并发变更，请刷新版本后重试");
        }
        return toPlanResponse(planRepository.findById(planId).orElseThrow());
    }

    // ============================== 紧急抢占（供 RunwayService 同事务调用） ==============================

    /**
     * 接报紧急关闭时在同一事务内抢占该跑道的例行计划：
     * 未开始（FROZEN/REVIEW_PENDING）计划取消；已开始（RUNNING）计划中止并记录紧急事件ID、释放巡检占用。
     * 已保存检查项原样保留；终态计划不动。调用方须已持有跑道行锁。
     */
    public void preemptPlansForEmergency(String runwayId, long emergencyEventId, Instant now) {
        List<InspectionPlanRecord> notStarted = planRepository.findByRunwayAndStatuses(runwayId,
                List.of(PlanStatus.FROZEN, PlanStatus.REVIEW_PENDING));
        for (InspectionPlanRecord plan : notStarted) {
            planRepository.preemptUpdate(plan.id(), PlanStatus.CANCELLED, null, null, now);
        }
        List<InspectionPlanRecord> running = planRepository.findByRunwayAndStatuses(runwayId,
                List.of(PlanStatus.RUNNING));
        for (InspectionPlanRecord plan : running) {
            planRepository.preemptUpdate(plan.id(), PlanStatus.ABORTED, null, emergencyEventId, now);
        }
        if (!running.isEmpty()) {
            coordinationRepository.release(runwayId);
        }
    }

    // ============================== 查询 ==============================

    /**
     * 计划详情：状态、版本、冻结证据、已保存检查项与完成/迟到计数（可复算）。
     */
    @Transactional(readOnly = true)
    public PlanResponse getPlan(long planId) {
        return toPlanResponse(mustFindPlan(planId));
    }

    // ============================== 辅助方法 ==============================

    private <T> T executeIdempotent(String scope, String requestId, String fingerprint, Supplier<T> action,
                                    Class<T> type) {
        return idempotencyExecutor.execute(scope, requestId, fingerprint, action, type);
    }

    private InspectionPlanRecord mustFindPlan(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "例行巡检计划不存在: " + planId));
    }

    private FlightCalendarRecord requireCalendarVersion(int expectedVersion) {
        FlightCalendarRecord calendar = calendarRepository.findCurrent()
                .orElseThrow(() -> ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                        "航班日历尚未发布，expectedVersion=" + expectedVersion + " 无法冻结"));
        if (calendar.version() != expectedVersion) {
            throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                    "日历版本不匹配：期望" + expectedVersion + "，当前" + calendar.version());
        }
        return calendar;
    }

    /**
     * 半开区间正长度相交判定：w.start < inspectEnd 且 inspectStart < w.end；端点相接不相交。
     */
    private List<String> overlappingFlights(List<FlightWindowRecord> windows, Instant inspectStart,
                                            Instant inspectEnd) {
        List<String> conflicts = new ArrayList<>();
        for (FlightWindowRecord window : windows) {
            if (window.occupyStart().isBefore(inspectEnd) && inspectStart.isBefore(window.occupyEnd())) {
                conflicts.add(window.flightNo());
            }
        }
        return conflicts;
    }

    private PlanFreezeEvidence buildEvidence(int calendarVersion, String runwayId, Instant windowStart,
                                             Instant windowEnd, Instant inspectStart, Instant inspectEnd,
                                             List<FlightWindowRecord> windows, List<String> conflicts) {
        List<PlanFreezeEvidence.FlightWindowView> views = windows.stream()
                .map(w -> new PlanFreezeEvidence.FlightWindowView(w.flightNo(), w.occupyStart(), w.occupyEnd()))
                .toList();
        return new PlanFreezeEvidence(calendarVersion, runwayId, windowStart, windowEnd, inspectStart,
                inspectEnd, views, conflicts);
    }

    private String writeEvidence(PlanFreezeEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("冻结证据序列化失败", e);
        }
    }

    private PlanFreezeEvidence readEvidence(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanFreezeEvidence.class);
        } catch (Exception e) {
            throw new IllegalStateException("冻结证据反序列化失败", e);
        }
    }

    private PlanResponse toPlanResponse(InspectionPlanRecord plan) {
        List<PlanCheckItemView> checks = checkItemRepository.findByPlanId(plan.id()).stream()
                .map(c -> new PlanCheckItemView(c.id(), c.itemCode(), c.result().name(), c.remark(),
                        c.observedAt()))
                .toList();
        List<PlanCompletionRecord> completions = completionRepository.findByPlanId(plan.id());
        int lateCount = (int) completions.stream().filter(PlanCompletionRecord::late).count();
        return new PlanResponse(plan.id(), plan.runwayId(), plan.status().name(), plan.version(),
                plan.windowStart(), plan.windowEnd(), plan.plannedStartAt(), plan.plannedEndAt(),
                plan.frozenCalendarVersion(), readEvidence(plan.conflictEvidence()), plan.abortedByEventId(),
                checks, completions.size(), lateCount, plan.updatedAt());
    }

    private String[] buildCalendarTokens(CalendarPublishRequest request) {
        List<String> tokens = new ArrayList<>();
        tokens.add(request.requestId());
        tokens.add(String.valueOf(request.expectedVersion()));
        tokens.add(request.publishedAt().toString());
        tokens.add("windows.count=" + request.windows().size());
        for (FlightOccupancyInput window : request.windows()) {
            tokens.add(window.runwayId());
            tokens.add(window.flightNo());
            tokens.add(window.occupyStart().toString());
            tokens.add(window.occupyEnd().toString());
        }
        return tokens.toArray(new String[0]);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isConstraint(DuplicateKeyException e, String marker) {
        return String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains(marker.toLowerCase(Locale.ROOT));
    }
}
