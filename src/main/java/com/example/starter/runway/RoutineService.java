package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 例行跑道巡检计划服务：航班日历版本、计划发布/开始/检查回报/完成回报、日历影响查询。
 *
 * <p>并发与一致性要点：</p>
 * <ul>
 *   <li>例行开始与紧急接报在同一跑道上先取 runway_lock 行锁串行化，得到唯一占用状态；跨跑道持不同行互不阻塞。</li>
 *   <li>同跑道同窗唯一获准由 routine_plan 唯一索引（slot_flag）保证。</li>
 *   <li>计划聚合带乐观锁 version（从1开始，成功变更加1），写操作携带 expectedVersion，不符返回409。</li>
 *   <li>日历版本仅追加；发布在事务内锁定最大版本行分配版本号，并发发布得到连续唯一新版本。</li>
 * </ul>
 */
@Service
public class RoutineService {

    static final String SCOPE_CALENDAR = "calendar-publish";
    static final String SCOPE_PLAN = "plan-publish";
    static final String SCOPE_START = "plan-start";
    static final String SCOPE_CHECK = "plan-check";
    static final String SCOPE_COMPLETION = "plan-completion";

    private final FlightCalendarRepository calendarRepository;
    private final FlightOccupancyRepository occupancyRepository;
    private final RoutinePlanRepository planRepository;
    private final RoutineCheckItemRepository checkItemRepository;
    private final RoutineCompletionRepository completionRepository;
    private final RunwayEventRepository eventRepository;
    private final RunwayLockRepository runwayLockRepository;
    private final ConstructionLockGuard constructionLockGuard;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RoutineService(FlightCalendarRepository calendarRepository,
                          FlightOccupancyRepository occupancyRepository,
                          RoutinePlanRepository planRepository,
                          RoutineCheckItemRepository checkItemRepository,
                          RoutineCompletionRepository completionRepository,
                          RunwayEventRepository eventRepository,
                          RunwayLockRepository runwayLockRepository,
                          ConstructionLockGuard constructionLockGuard,
                          IdempotencyExecutor idempotencyExecutor,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.calendarRepository = calendarRepository;
        this.occupancyRepository = occupancyRepository;
        this.planRepository = planRepository;
        this.checkItemRepository = checkItemRepository;
        this.completionRepository = completionRepository;
        this.eventRepository = eventRepository;
        this.runwayLockRepository = runwayLockRepository;
        this.constructionLockGuard = constructionLockGuard;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================= 航班日历 =============================

    /**
     * 发布新航班日历版本：仅追加，版本+1；并发发布以 expectedVersion 乐观判定，仅一个成功。
     * 发布后所有“尚未开始（APPROVED）”计划置待重审，已开始计划不自动平移。requestId 幂等。
     */
    public CalendarPublishResponse publishCalendar(CalendarPublishRequest request) {
        for (FlightWindowInput window : request.occupancies()) {
            if (!window.windowEnd().isAfter(window.windowStart())) {
                throw ApiException.badRequest("INVALID_OCCUPANCY_WINDOW",
                        "航班占用窗结束时刻必须晚于开始时刻: " + window.runwayId());
            }
        }
        List<List<String>> windowFields = request.occupancies().stream()
                .map(w -> List.of(w.runwayId(), w.windowStart().toString(), w.windowEnd().toString()))
                .toList();
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_CALENDAR,
                List.of(request.requestId(), String.valueOf(request.expectedVersion()),
                        request.publishedAt().toString()),
                windowFields);
        try {
            return idempotencyExecutor.execute(SCOPE_CALENDAR, request.requestId(), fingerprint,
                    () -> doPublishCalendar(request), CalendarPublishResponse.class);
        } catch (DuplicateKeyException e) {
            CalendarPublishResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CALENDAR,
                    request.requestId(), fingerprint, CalendarPublishResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isVersionConflict(e)) {
                throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                        "日历版本已被并发发布推进，请基于最新版本重试");
            }
            throw e;
        }
    }

    private CalendarPublishResponse doPublishCalendar(CalendarPublishRequest request) {
        Instant now = clock.instant();
        Integer current = calendarRepository.findMaxVersionForUpdate()
                .orElseThrow(() -> ApiException.badRequest("CALENDAR_NOT_INITIALIZED", "航班日历尚未初始化"));
        if (current != request.expectedVersion()) {
            throw ApiException.conflict("CALENDAR_VERSION_CONFLICT",
                    "日历版本已变更，当前最新版本=" + current + "，请求 expectedVersion=" + request.expectedVersion());
        }
        int newVersion = current + 1;
        calendarRepository.insert(newVersion, request.publishedAt(), request.requestId(), now);
        List<FlightOccupancyWindow> windows = request.occupancies().stream()
                .map(w -> new FlightOccupancyWindow(0L, newVersion, w.runwayId(), w.windowStart(), w.windowEnd()))
                .toList();
        occupancyRepository.insertBatch(newVersion, windows);

        // 仅影响尚未开始的获准计划；快照其ID用于响应，再统一定为待重审。已开始计划不动。
        List<Long> approvedIds = planRepository.findApprovedIds();
        if (!approvedIds.isEmpty()) {
            planRepository.markApprovedStaleForReview(now);
        }
        return new CalendarPublishResponse(newVersion, request.publishedAt(), approvedIds);
    }

    // ============================= 例行计划发布 =============================

    /**
     * 计划申请发布：校验落窗与冻结日历版本下的占用冲突，冻结冲突证据。
     * 正长度相交拒绝（422，附证据），端点相接合法；同跑道同窗仅一个获准。requestId 幂等。
     */
    public PlanResponse publishPlan(PlanPublishRequest request) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw ApiException.badRequest("INVALID_PLAN_WINDOW", "巡检窗口结束时刻必须晚于开始时刻");
        }
        Instant inspectionEnd = request.windowStart().plus(request.durationMinutes(), ChronoUnit.MINUTES);
        if (inspectionEnd.isAfter(request.windowEnd())) {
            throw ApiException.unprocessable("INSPECTION_NOT_WITHIN_WINDOW",
                    "巡检时长须完整落在窗口内：巡检结束 " + inspectionEnd + " 晚于窗口结束 " + request.windowEnd());
        }
        int latest = latestCalendarVersion();
        if (request.calendarVersion() != latest) {
            throw ApiException.unprocessable("CALENDAR_VERSION_STALE",
                    "发布须依据最新航班日历版本，最新=" + latest + "，提交=" + request.calendarVersion());
        }
        String fingerprint = StructuredFingerprint.sha256(SCOPE_PLAN,
                request.requestId(), request.runwayId(), request.windowStart().toString(),
                request.windowEnd().toString(), String.valueOf(request.durationMinutes()),
                String.valueOf(request.calendarVersion()));
        try {
            return idempotencyExecutor.execute(SCOPE_PLAN, request.requestId(), fingerprint,
                    () -> doPublishPlan(request, inspectionEnd), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_PLAN, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isSlotConflict(e)) {
                throw ApiException.unprocessable("PLAN_SLOT_OCCUPIED",
                        "同跑道同窗已存在一个获准（未终态）例行计划");
            }
            throw e;
        }
    }

    private PlanResponse doPublishPlan(PlanPublishRequest request, Instant inspectionEnd) {
        List<FlightOccupancyWindow> windows =
                occupancyRepository.findByVersionAndRunway(request.calendarVersion(), request.runwayId());
        ConflictEvidence evidence = buildEvidence(request.runwayId(), request.calendarVersion(),
                request.windowStart(), request.windowEnd(), request.windowStart(), inspectionEnd, windows);
        if (evidence.conflict()) {
            throw ApiException.unprocessable("OCCUPANCY_CONFLICT",
                    "巡检区间与合成航班占用窗正长度相交，拒绝发布", writeEvidence(evidence));
        }
        Instant now = clock.instant();
        RoutinePlan plan = new RoutinePlan(0L, request.runwayId(), request.windowStart(), request.windowEnd(),
                request.durationMinutes(), request.calendarVersion(), 1, RoutinePlanStatus.APPROVED,
                writeEvidence(evidence), 1, null, null, null, null, request.requestId(), now, now);
        RoutinePlan saved;
        try {
            saved = planRepository.insert(plan);
        } catch (DuplicateKeyException e) {
            if (isSlotConflict(e)) {
                throw ApiException.unprocessable("PLAN_SLOT_OCCUPIED",
                        "同跑道同窗已存在一个获准（未终态）例行计划");
            }
            throw e;
        }
        return toResponse(saved);
    }

    // ============================= 例行巡检开始 =============================

    /**
     * 开始：取跑道行锁后重新确认日历版本（按最新占用窗重算，冲突拒绝）且无进行中紧急关闭。
     * expectedVersion 乐观锁；requestId 幂等。事务以幂等执行器为界（不在本方法另开事务）。
     */
    public PlanResponse startPlan(long planId, PlanStartRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_START,
                request.requestId(), String.valueOf(planId), String.valueOf(request.expectedVersion()),
                request.startedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_START, request.requestId(), fingerprint,
                    () -> doStartPlan(planId, request), PlanResponse.class);
        } catch (DuplicateKeyException e) {
            PlanResponse replayed = idempotencyExecutor.tryReplay(SCOPE_START, request.requestId(),
                    fingerprint, PlanResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanResponse doStartPlan(long planId, PlanStartRequest request) {
        // 先读计划拿到跑道，再取该跑道行锁，随后行锁重读计划，确保与紧急接报串行化。
        String runwayId = planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("ROUTINE_PLAN_NOT_FOUND", "例行计划不存在: " + planId))
                .runwayId();
        runwayLockRepository.lockRunway(runwayId);
        RoutinePlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("ROUTINE_PLAN_NOT_FOUND", "例行计划不存在: " + planId));
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更，当前=" + plan.version() + "，expectedVersion=" + request.expectedVersion());
        }
        if (plan.status() != RoutinePlanStatus.APPROVED && plan.status() != RoutinePlanStatus.NEEDS_REVIEW) {
            throw ApiException.unprocessable("PLAN_NOT_STARTABLE",
                    "仅获准或待重审（未开始）计划可开始，当前状态=" + plan.status());
        }
        if (request.startedAt().isBefore(plan.windowStart()) || !request.startedAt().isBefore(plan.windowEnd())) {
            throw ApiException.unprocessable("START_NOT_WITHIN_WINDOW", "开始时刻须落在巡检半开窗口内");
        }
        Instant actualInspectionEnd = request.startedAt().plus(plan.durationMinutes(), ChronoUnit.MINUTES);
        if (actualInspectionEnd.isAfter(plan.windowEnd())) {
            throw ApiException.unprocessable("INSPECTION_NOT_WITHIN_WINDOW",
                    "按实际开始时刻推算，巡检时长超出窗口");
        }
        if (eventRepository.existsActiveByRunway(plan.runwayId())) {
            throw ApiException.unprocessable("EMERGENCY_CLOSURE_ACTIVE",
                    "跑道存在进行中紧急关闭事件，例行巡检不得开始");
        }
        // 施工生效阻断整跑道新使用：存在活动施工闭锁时例行巡检不得开始；未受控跑道原行为不变。
        constructionLockGuard.assertNoActiveLock(plan.runwayId());
        int latest = latestCalendarVersion();
        List<FlightOccupancyWindow> windows =
                occupancyRepository.findByVersionAndRunway(latest, plan.runwayId());
        ConflictEvidence evidence = buildEvidence(plan.runwayId(), latest, plan.windowStart(), plan.windowEnd(),
                request.startedAt(), actualInspectionEnd, windows);
        if (evidence.conflict()) {
            throw ApiException.unprocessable("CALENDAR_REVIEW_CONFLICT",
                    "依据最新日历版本(" + latest + ")重审存在占用冲突，不得开始", writeEvidence(evidence));
        }
        Instant now = clock.instant();
        RoutinePlan started = new RoutinePlan(plan.id(), plan.runwayId(), plan.windowStart(), plan.windowEnd(),
                plan.durationMinutes(), latest, plan.version() + 1, RoutinePlanStatus.STARTED,
                writeEvidence(evidence), plan.slotFlag(), null, request.startedAt(), null, null,
                plan.requestId(), plan.createdAt(), now);
        planRepository.update(started);
        return toResponse(planRepository.findById(planId).orElseThrow());
    }

    // ============================= 检查项回报 =============================

    /**
     * 检查项回报：进行中（STARTED）正常保存并使聚合版本+1；
     * 计划被紧急取消/中止或已关闭后仅登记迟到（late=1），不改变状态、不开放跑道。requestId 幂等。
     * 事务以幂等执行器为界。
     */
    public PlanCheckReportResponse reportChecks(long planId, PlanCheckReportRequest request) {
        if (request.items().isEmpty()) {
            throw ApiException.badRequest("EMPTY_CHECK_ITEMS", "检查项集合不能为空");
        }
        List<List<String>> itemFields = request.items().stream()
                .map(i -> List.of(i.itemCode(), i.itemText(), String.valueOf(i.passed()),
                        i.completedAt().toString()))
                .toList();
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_CHECK,
                List.of(request.requestId(), String.valueOf(planId), String.valueOf(request.expectedVersion()),
                        request.reportedAt().toString()),
                itemFields);
        try {
            return idempotencyExecutor.execute(SCOPE_CHECK, request.requestId(), fingerprint,
                    () -> doReportChecks(planId, request), PlanCheckReportResponse.class);
        } catch (DuplicateKeyException e) {
            PlanCheckReportResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CHECK,
                    request.requestId(), fingerprint, PlanCheckReportResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanCheckReportResponse doReportChecks(long planId, PlanCheckReportRequest request) {
        RoutinePlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("ROUTINE_PLAN_NOT_FOUND", "例行计划不存在: " + planId));
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更，当前=" + plan.version() + "，expectedVersion=" + request.expectedVersion());
        }
        boolean late = switch (plan.status()) {
            case STARTED -> false;
            case ABORTED, CANCELLED, COMPLETED -> true;
            default -> throw ApiException.unprocessable("PLAN_NOT_STARTED",
                    "巡检尚未开始，不受理检查项回报，当前状态=" + plan.status());
        };
        Instant now = clock.instant();
        List<PlanCheckReportResponse.SavedCheckItem> saved = new ArrayList<>();
        for (CheckItemInput item : request.items()) {
            RoutineCheckItem row = checkItemRepository.insert(new RoutineCheckItem(0L, planId, item.itemCode(),
                    item.itemText(), item.passed(), item.completedAt(), late, request.requestId(), now));
            saved.add(new PlanCheckReportResponse.SavedCheckItem(row.id(), row.itemCode(), row.itemText(),
                    row.passed(), row.late()));
        }
        RoutinePlan current = plan;
        if (!late) {
            // 进行中回报：追加检查项属聚合变更，版本+1，状态仍为 STARTED。
            RoutinePlan changed = new RoutinePlan(plan.id(), plan.runwayId(), plan.windowStart(), plan.windowEnd(),
                    plan.durationMinutes(), plan.calendarVersion(), plan.version() + 1, plan.status(),
                    plan.evidence(), plan.slotFlag(), null, plan.startedAt(), plan.completedAt(),
                    plan.preemptedAt(), plan.requestId(), plan.createdAt(), now);
            planRepository.update(changed);
            current = planRepository.findById(planId).orElseThrow();
        }
        return new PlanCheckReportResponse(planId, current.status().name(), current.version(), late, saved);
    }

    // ============================= 完成回报 =============================

    /**
     * 完成回报：进行中正常完成只关闭计划（COMPLETED，释放获准位），不触发恢复开放/通告；
     * 被紧急取消/中止或已完成后的回报仅登记迟到，不开放跑道。requestId 幂等。事务以幂等执行器为界。
     */
    public PlanCompletionResponse completePlan(long planId, PlanCompletionRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_COMPLETION,
                request.requestId(), String.valueOf(planId), String.valueOf(request.expectedVersion()),
                request.completedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_COMPLETION, request.requestId(), fingerprint,
                    () -> doCompletePlan(planId, request), PlanCompletionResponse.class);
        } catch (DuplicateKeyException e) {
            PlanCompletionResponse replayed = idempotencyExecutor.tryReplay(SCOPE_COMPLETION,
                    request.requestId(), fingerprint, PlanCompletionResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private PlanCompletionResponse doCompletePlan(long planId, PlanCompletionRequest request) {
        RoutinePlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("ROUTINE_PLAN_NOT_FOUND", "例行计划不存在: " + planId));
        if (plan.version() != request.expectedVersion()) {
            throw ApiException.conflict("PLAN_VERSION_CONFLICT",
                    "计划版本已变更，当前=" + plan.version() + "，expectedVersion=" + request.expectedVersion());
        }
        Instant now = clock.instant();
        if (plan.status() == RoutinePlanStatus.STARTED) {
            completionRepository.insert(new RoutineCompletionReport(0L, planId, request.completedAt(), false,
                    request.requestId(), now));
            RoutinePlan completed = new RoutinePlan(plan.id(), plan.runwayId(), plan.windowStart(),
                    plan.windowEnd(), plan.durationMinutes(), plan.calendarVersion(), plan.version() + 1,
                    RoutinePlanStatus.COMPLETED, plan.evidence(), plan.slotFlag(), plan.emergencyEventId(),
                    plan.startedAt(), request.completedAt(), plan.preemptedAt(), plan.requestId(),
                    plan.createdAt(), now);
            planRepository.update(completed);
            RoutinePlan saved = planRepository.findById(planId).orElseThrow();
            return new PlanCompletionResponse(planId, saved.status().name(), saved.version(), false,
                    request.completedAt());
        }
        if (plan.status() == RoutinePlanStatus.ABORTED || plan.status() == RoutinePlanStatus.CANCELLED
                || plan.status() == RoutinePlanStatus.COMPLETED) {
            // 被抢占后（或已关闭后）的迟到完成回报：只登记，不复活、不开放跑道，计划聚合不变更。
            completionRepository.insert(new RoutineCompletionReport(0L, planId, request.completedAt(), true,
                    request.requestId(), now));
            return new PlanCompletionResponse(planId, plan.status().name(), plan.version(), true,
                    request.completedAt());
        }
        throw ApiException.unprocessable("PLAN_NOT_STARTED",
                "巡检尚未开始，不受理完成回报，当前状态=" + plan.status());
    }

    // ============================= 查询 =============================

    @Transactional(readOnly = true)
    public PlanResponse getPlan(long planId) {
        return toResponse(planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("ROUTINE_PLAN_NOT_FOUND", "例行计划不存在: " + planId)));
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> listPlans(String runwayId) {
        return planRepository.findByRunway(runwayId).stream().map(this::toResponse).toList();
    }

    /**
     * 日历影响查询：返回该版本占用窗，以及冻结在该版本、因变更待重审的未开始计划。
     */
    @Transactional(readOnly = true)
    public CalendarImpactResponse getCalendarImpact(int version) {
        FlightCalendarVersion calendar = calendarRepository.findByVersion(version)
                .orElseThrow(() -> ApiException.notFound("CALENDAR_VERSION_NOT_FOUND",
                        "航班日历版本不存在: " + version));
        List<OccupancyView> occupancies = occupancyRepository.findByVersion(version).stream()
                .map(w -> new OccupancyView(w.windowStart(), w.windowEnd()))
                .toList();
        // 待重审且冻结版本早于查询版本：即因截至该版本的日历变更而受影响的未开始计划。
        List<PlanResponse> impacted = planRepository.findNeedsReview().stream()
                .filter(p -> p.calendarVersion() < version)
                .map(this::toResponse)
                .toList();
        return new CalendarImpactResponse(version, calendar.publishedAt(), occupancies, impacted);
    }

    // ============================= 内部工具 =============================

    private int latestCalendarVersion() {
        return calendarRepository.findMaxVersion()
                .orElseThrow(() -> ApiException.badRequest("CALENDAR_NOT_INITIALIZED", "航班日历尚未初始化"));
    }

    /**
     * 构造结构化冲突证据：实际巡检区间为 [inspectionStart, inspectionEnd)，
     * 与占用窗 [s,e) 正长度相交（max(start) &lt; min(end)）即冲突；端点相接（相等）不相交，合法。
     */
    private ConflictEvidence buildEvidence(String runwayId, int calendarVersion, Instant windowStart,
                                           Instant windowEnd, Instant inspectionStart, Instant inspectionEnd,
                                           List<FlightOccupancyWindow> windows) {
        List<OccupancyView> considered = windows.stream()
                .map(w -> new OccupancyView(w.windowStart(), w.windowEnd()))
                .toList();
        List<OccupancyView> conflicting = windows.stream()
                .filter(w -> inspectionStart.isBefore(w.windowEnd()) && w.windowStart().isBefore(inspectionEnd))
                .map(w -> new OccupancyView(w.windowStart(), w.windowEnd()))
                .toList();
        return new ConflictEvidence(runwayId, calendarVersion, windowStart, windowEnd, inspectionStart,
                inspectionEnd, !conflicting.isEmpty(), considered, conflicting);
    }

    private String writeEvidence(ConflictEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冲突证据序列化失败", e);
        }
    }

    private PlanResponse toResponse(RoutinePlan plan) {
        Instant inspectionEnd = plan.startedAt() != null
                ? plan.startedAt().plus(plan.durationMinutes(), ChronoUnit.MINUTES)
                : plan.windowStart().plus(plan.durationMinutes(), ChronoUnit.MINUTES);
        return new PlanResponse(plan.id(), plan.runwayId(), plan.windowStart(), plan.windowEnd(),
                plan.durationMinutes(), plan.calendarVersion(), plan.version(), plan.status().name(),
                inspectionEnd, plan.emergencyEventId(), plan.startedAt(), plan.completedAt(),
                plan.preemptedAt(), plan.evidence());
    }

    private boolean isSlotConflict(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("uk_routine_plan_slot");
    }

    private boolean isVersionConflict(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("uk_flight_calendar_version");
    }
}
