package com.example.starter.runway;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.runway.IdempotencyExecutor.ReplayException;
import com.example.starter.runway.dto.ClosureDetailView;
import com.example.starter.runway.dto.ClosureEventView;
import com.example.starter.runway.dto.InspectionRequest;
import com.example.starter.runway.dto.InspectionResponse;
import com.example.starter.runway.dto.InspectionView;
import com.example.starter.runway.dto.NotamView;
import com.example.starter.runway.dto.ReopenRequest;
import com.example.starter.runway.dto.ReopenResponse;
import com.example.starter.runway.dto.ReportClosureRequest;

/**
 * 跑道临时关闭巡检与开放决策服务。
 *
 * <p>流转：接报→临时关闭→巡检评估→恢复/持续关闭→归档。
 * 接报、评估与恢复均以 requestId 幂等；恢复开放与航行通告发布在同一事务内完成。
 */
@Service
public class RunwayClosureService {

    /** 低能见度阈值（合成 RVR，米）：低于该值巡检须双人执行。 */
    public static final int LOW_VISIBILITY_RVR_METERS = 550;

    /** 道面摩擦系数合格阈值：低于 0.35 须持续关闭，恰 0.35 合格。 */
    public static final BigDecimal FRICTION_THRESHOLD = new BigDecimal("0.350");

    /** 巡检开始时限：关闭后满 15 分钟未开始判定迟延并升级。 */
    public static final Duration INSPECTION_START_LIMIT = Duration.ofMinutes(15);

    private static final String STATUS_TEMP_CLOSED = "TEMP_CLOSED";
    private static final String STATUS_INSPECTING = "INSPECTING";
    private static final String STATUS_REOPENED = "REOPENED";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final RunwayClosureRepository repository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public RunwayClosureService(RunwayClosureRepository repository,
            IdempotencyExecutor idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 接报：FOD 或道面破损即触发临时关闭，同步发布关闭通告。
     * 同一跑道同时刻只允许一个进行中关闭事件。
     */
    @Transactional
    public String reportClosure(String runwayId, ReportClosureRequest request) {
        Instant reportedAt = request.reportedAt() != null ? request.reportedAt() : Instant.now(clock);
        String businessFields = String.join("|", runwayId, request.eventType(), reportedAt.toString());
        try {
            return idempotency.execute("REPORT", request.requestId(), businessFields, () -> {
                Instant now = Instant.now(clock);
                long eventId = insertEvent(runwayId, request, reportedAt, now);
                repository.insertTimeline(eventId, null, STATUS_TEMP_CLOSED,
                        "接报" + request.eventType() + "，触发临时关闭", now);
                repository.insertNotam(eventId, "CLOSURE",
                        "NOTAMC RWY " + runwayId + " TEMPORARILY CLOSED DUE TO " + request.eventType(),
                        request.requestId(), now);
                return repository.findEventById(eventId).orElseThrow();
            });
        } catch (ReplayException replay) {
            return replay.getResponseBody();
        }
    }

    private long insertEvent(String runwayId, ReportClosureRequest request, Instant reportedAt, Instant now) {
        try {
            return repository.insertEvent(runwayId, request.eventType(), request.requestId(),
                    reportedAt, reportedAt, now);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("跑道 " + runwayId + " 已存在进行中的关闭事件");
        }
    }

    /**
     * 巡检评估：关闭后满 15 分钟未开始判定迟延并升级；
     * 低能见度须双人执行，单人记录不生效；终态后迟到记录只记迟到事件。
     */
    @Transactional
    public String recordInspection(long eventId, InspectionRequest request) {
        Instant startedAt = request.startedAt() != null ? request.startedAt() : Instant.now(clock);
        String businessFields = String.join("|",
                String.valueOf(eventId),
                String.valueOf(request.inspectorCount()),
                String.valueOf(request.rvrMeters()),
                request.frictionCoefficient().stripTrailingZeros().toPlainString(),
                request.assessment(),
                startedAt.toString());
        try {
            return idempotency.execute("INSPECT", request.requestId(), businessFields,
                    () -> doRecordInspection(eventId, request, startedAt));
        } catch (ReplayException replay) {
            return replay.getResponseBody();
        }
    }

    private InspectionResponse doRecordInspection(long eventId, InspectionRequest request, Instant startedAt) {
        ClosureEventView event = requireEvent(eventId);
        Instant now = Instant.now(clock);

        boolean terminal = STATUS_REOPENED.equals(event.status()) || STATUS_ARCHIVED.equals(event.status());
        boolean delayed = !startedAt.isBefore(event.closedAt().plus(INSPECTION_START_LIMIT));
        boolean lowVisibility = request.rvrMeters() < LOW_VISIBILITY_RVR_METERS;
        boolean crewSufficient = !lowVisibility || request.inspectorCount() >= 2;
        boolean effective = !terminal && crewSufficient;

        long inspectionId = repository.insertInspection(eventId, request.requestId(),
                request.inspectorCount(), request.rvrMeters(), request.frictionCoefficient(),
                request.assessment(), effective, terminal, delayed, startedAt, now);

        ClosureEventView updated = event;
        if (!terminal) {
            boolean escalated = event.escalated() || delayed;
            boolean repairRequired = event.repairRequired()
                    || (effective && "REPAIR_REQUIRED".equals(request.assessment()));
            boolean frictionPass = request.frictionCoefficient().compareTo(FRICTION_THRESHOLD) >= 0;
            // 复测合格仅由标记修复类之后的生效巡检产生，当次评估不计入复测
            boolean repairCleared = event.repairCleared() || (effective && event.repairRequired() && frictionPass);
            repository.updateEventFlags(eventId, escalated, repairRequired, repairCleared, now);

            if (delayed && !event.escalated()) {
                repository.insertTimeline(eventId, event.status(), event.status(),
                        "巡检迟延（关闭后满15分钟未开始），已升级", now);
            }
            if (effective && STATUS_TEMP_CLOSED.equals(event.status())) {
                repository.updateEventStatus(eventId, STATUS_INSPECTING, null, null, now);
                repository.insertTimeline(eventId, STATUS_TEMP_CLOSED, STATUS_INSPECTING,
                        "巡检开始，进入评估", now);
            }
            updated = repository.findEventById(eventId).orElseThrow();
        }

        InspectionView inspection = repository.findInspectionsByEvent(eventId).stream()
                .filter(row -> row.id() == inspectionId)
                .findFirst()
                .orElseThrow();
        return new InspectionResponse(inspection, updated);
    }

    /**
     * 恢复开放：最新生效巡检合格（摩擦系数不低于 0.35）且修复类事件复测合格方可开放；
     * 开放与航行通告发布在同一事务内完成。
     */
    @Transactional
    public String reopen(long eventId, ReopenRequest request) {
        String businessFields = String.valueOf(eventId);
        try {
            return idempotency.execute("REOPEN", request.requestId(), businessFields,
                    () -> doReopen(eventId, request));
        } catch (ReplayException replay) {
            return replay.getResponseBody();
        }
    }

    private ReopenResponse doReopen(long eventId, ReopenRequest request) {
        ClosureEventView event = requireEvent(eventId);
        if (STATUS_ARCHIVED.equals(event.status())) {
            throw ApiException.conflict("事件已归档，不得恢复开放");
        }
        if (STATUS_REOPENED.equals(event.status())) {
            throw ApiException.conflict("事件已恢复开放，请使用原 requestId 重放");
        }

        List<InspectionView> effectiveInspections = repository.findInspectionsByEvent(eventId).stream()
                .filter(InspectionView::effective)
                .toList();
        if (effectiveInspections.isEmpty()) {
            throw ApiException.conflict("无生效巡检记录，不得恢复开放");
        }
        InspectionView latest = effectiveInspections.get(effectiveInspections.size() - 1);
        if (latest.frictionCoefficient().compareTo(FRICTION_THRESHOLD) < 0) {
            throw ApiException.conflict("最新生效摩擦系数低于 0.35，须持续关闭");
        }
        if (event.repairRequired() && !event.repairCleared()) {
            throw ApiException.conflict("修复类事件须复测合格方可开放");
        }

        Instant now = Instant.now(clock);
        repository.updateEventStatus(eventId, STATUS_REOPENED, now, null, now);
        repository.insertTimeline(eventId, event.status(), STATUS_REOPENED,
                "巡检合格，恢复开放并同步发布通告", now);
        long notamId = repository.insertNotam(eventId, "REOPEN",
                "NOTAMC RWY " + event.runwayId() + " REOPENED AFTER INSPECTION",
                request.requestId(), now);

        ClosureEventView updated = repository.findEventById(eventId).orElseThrow();
        NotamView notam = repository.findNotamsByEvent(eventId).stream()
                .filter(row -> row.id() == notamId)
                .findFirst()
                .orElseThrow();
        return new ReopenResponse(updated, notam);
    }

    /**
     * 归档：终态。重复归档返回幂等成功；归档后迟到巡检记录只记迟到事件。
     */
    @Transactional
    public ClosureEventView archive(long eventId) {
        ClosureEventView event = requireEvent(eventId);
        if (STATUS_ARCHIVED.equals(event.status())) {
            return event;
        }
        if (!STATUS_REOPENED.equals(event.status())) {
            throw ApiException.conflict("仅恢复开放后可归档");
        }
        Instant now = Instant.now(clock);
        repository.updateEventStatus(eventId, STATUS_ARCHIVED, null, now, now);
        repository.insertTimeline(eventId, event.status(), STATUS_ARCHIVED, "事件归档（终态）", now);
        return repository.findEventById(eventId).orElseThrow();
    }

    /**
     * 事件详情：状态时间线、系数轨迹与通告记录均可复算。
     */
    @Transactional(readOnly = true)
    public ClosureDetailView detail(long eventId) {
        ClosureEventView event = requireEvent(eventId);
        return new ClosureDetailView(
                event,
                repository.findTimelineByEvent(eventId),
                repository.findInspectionsByEvent(eventId),
                repository.findNotamsByEvent(eventId));
    }

    private ClosureEventView requireEvent(long eventId) {
        return repository.findEventById(eventId)
                .orElseThrow(() -> ApiException.notFound("关闭事件不存在: " + eventId));
    }
}
