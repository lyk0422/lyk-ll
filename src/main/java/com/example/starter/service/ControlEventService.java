package com.example.starter.service;

import com.example.starter.model.ControlEvent;
import com.example.starter.model.ControlEventStatus;
import com.example.starter.model.ControlEventType;
import com.example.starter.model.LineStatus;
import com.example.starter.model.MaintenanceOrder;
import com.example.starter.model.ReopenRole;
import com.example.starter.model.WindLine;
import com.example.starter.model.WindSegment;
import com.example.starter.model.WindStation;
import com.example.starter.repo.BoardingRepository;
import com.example.starter.repo.ConfirmationRepository;
import com.example.starter.repo.ControlEventRepository;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.MaintenanceRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.StationRepository;
import com.example.starter.repo.WindReadingRepository;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.BoardingResponse;
import com.example.starter.web.dto.CabinReleaseRequest;
import com.example.starter.web.dto.CabinReleaseResponse;
import com.example.starter.web.dto.ClearanceRequest;
import com.example.starter.web.dto.ClearanceResponse;
import com.example.starter.web.dto.EventView;
import com.example.starter.web.dto.LineView;
import com.example.starter.web.dto.ReopenApplicationRequest;
import com.example.starter.web.dto.ReopenApplicationResponse;
import com.example.starter.web.dto.ReopenConfirmationRequest;
import com.example.starter.web.dto.ReopenConfirmationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 管制事件业务：缓停禁止上客与逐段放行、紧急停车救援双人确认、复开申请与双岗确认。
 */
@Service
public class ControlEventService {

    /** 复开判定窗口：风速回落至 15m/s 以下并持续 15 分钟，恰满即可申请 */
    static final Duration RECOVERY_WINDOW = Duration.ofMinutes(15);
    /** 复开风速阈值（m/s），须严格低于该值 */
    static final double RECOVERY_THRESHOLD_MS = 15.0;

    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final SegmentRepository segmentRepository;
    private final WindReadingRepository readingRepository;
    private final ControlEventRepository eventRepository;
    private final ConfirmationRepository confirmationRepository;
    private final BoardingRepository boardingRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;
    private final JsonSupport json;

    public ControlEventService(LineRepository lineRepository, StationRepository stationRepository,
                               SegmentRepository segmentRepository, WindReadingRepository readingRepository,
                               ControlEventRepository eventRepository,
                               ConfirmationRepository confirmationRepository,
                               BoardingRepository boardingRepository,
                               MaintenanceRepository maintenanceRepository,
                               IdempotencyService idempotencyService, TimeProvider timeProvider,
                               JsonSupport json) {
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.segmentRepository = segmentRepository;
        this.readingRepository = readingRepository;
        this.eventRepository = eventRepository;
        this.confirmationRepository = confirmationRepository;
        this.boardingRepository = boardingRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
        this.json = json;
    }

    /**
     * 上客：管制期间（缓停/紧急停车）禁止上客；检修封锁生效期间同样禁止，
     * 每次调用直接按当前时刻检查检修状态，两类阻断分别留证。
     */
    public ResponseEntity<String> board(long lineId, BoardingRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "boarding", String.valueOf(lineId),
                String.valueOf(request.passengerCount()));
        return idempotencyService.execute("boarding", request.requestId(), fingerprint, () -> {
            WindLine line = lineRepository.findByIdForUpdate(lineId)
                    .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
            if (line.status() != LineStatus.NORMAL) {
                throw ApiException.conflict("线路管制中禁止上客: " + line.status());
            }
            requireNoMaintenanceBlock(lineId);
            long boardingId = boardingRepository.insert(lineId, request.requestId(), request.passengerCount());
            return json.ok(new BoardingResponse(boardingId, lineId, true));
        });
    }

    /**
     * 缓停在途吊厢按站点逐段放行，同一事件同一分段只放行一次。
     */
    public ResponseEntity<String> releaseCabins(long eventId, long segmentId, CabinReleaseRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "cabin-release", String.valueOf(eventId),
                String.valueOf(segmentId), request.operatorId());
        return idempotencyService.execute("cabin-release", request.requestId(), fingerprint, () -> {
            ControlEvent event = requireEvent(eventId);
            if (event.type() != ControlEventType.SLOW_STOP) {
                throw ApiException.unprocessable("仅缓停事件支持吊厢逐段放行");
            }
            if (event.status() == ControlEventStatus.RESUMED) {
                throw ApiException.conflict("管制事件已归档，禁止放行操作");
            }
            requireSegment(event, segmentId);
            boolean inserted = confirmationRepository.insertCabinRelease(eventId, segmentId, request.operatorId());
            Instant releasedAt = confirmationRepository.findCabinReleaseAt(eventId, segmentId);
            return json.ok(new CabinReleaseResponse(eventId, segmentId, inserted || releasedAt != null, releasedAt));
        });
    }

    /**
     * 紧急停车救援分段清空确认：须双人确认，单人确认（含同一人重复确认）不生效。
     */
    public ResponseEntity<String> confirmClearance(long eventId, long segmentId, ClearanceRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "clearance", String.valueOf(eventId),
                String.valueOf(segmentId), request.confirmerId());
        return idempotencyService.execute("clearance", request.requestId(), fingerprint, () -> {
            ControlEvent event = requireEvent(eventId);
            if (event.type() != ControlEventType.EMERGENCY_STOP) {
                throw ApiException.unprocessable("仅紧急停车事件需要救援清空确认");
            }
            if (event.status() != ControlEventStatus.ACTIVE) {
                throw ApiException.conflict("事件当前状态不允许清空确认: " + event.status());
            }
            requireSegment(event, segmentId);
            confirmationRepository.insertClearance(eventId, segmentId, request.confirmerId());
            List<String> confirmers = confirmationRepository.listConfirmers(eventId, segmentId);
            return json.ok(new ClearanceResponse(eventId, segmentId, confirmers.size(),
                    confirmers.size() >= 2, confirmers));
        });
    }

    /**
     * 复开申请：风速回落至 15m/s 以下并持续 15 分钟（恰满即可）；
     * 紧急停车事件还须全部分段双人确认清空。
     */
    public ResponseEntity<String> applyReopen(long eventId, ReopenApplicationRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "reopen-application", String.valueOf(eventId),
                request.requestedAt().toString());
        return idempotencyService.execute("reopen-application", request.requestId(), fingerprint, () -> {
            ControlEvent event = eventRepository.findByIdForUpdate(eventId)
                    .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
            if (event.status() != ControlEventStatus.ACTIVE) {
                throw ApiException.conflict("事件当前状态不允许申请复开: " + event.status());
            }
            List<WindSegment> segments = segmentRepository.findByLineId(event.lineId());
            if (event.type() == ControlEventType.EMERGENCY_STOP
                    && confirmationRepository.countClearedSegments(eventId) < segments.size()) {
                throw ApiException.unprocessable("救援分段尚未全部双人确认清空");
            }
            Instant requestedAt = request.requestedAt();
            Instant windowStart = requestedAt.minus(RECOVERY_WINDOW);
            for (WindStation station : stationRepository.findByLineId(event.lineId())) {
                Instant min = readingRepository.minMeasuredAtInWindow(station.id(), windowStart, requestedAt);
                if (min == null || min.isAfter(windowStart)) {
                    throw ApiException.unprocessable("风速回落持续时长未恰满 15 分钟");
                }
                if (readingRepository.countAtOrAbove(station.id(), windowStart, requestedAt,
                        RECOVERY_THRESHOLD_MS) > 0) {
                    throw ApiException.unprocessable("风速未回落至 15m/s 以下");
                }
            }
            eventRepository.updateStatus(eventId, ControlEventStatus.REOPEN_REQUESTED);
            return json.ok(new ReopenApplicationResponse(eventId, ControlEventStatus.REOPEN_REQUESTED.name()));
        });
    }

    /**
     * 复开双岗确认：运营与机务各确认一次后事件归档复开，线路恢复运行；
     * 检修封锁生效期间禁止复开，每次调用直接按当前时刻检查检修状态。
     */
    public ResponseEntity<String> confirmReopen(long eventId, ReopenConfirmationRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), "reopen-confirmation", String.valueOf(eventId),
                request.role().name(), request.operatorId());
        return idempotencyService.execute("reopen-confirmation", request.requestId(), fingerprint, () -> {
            ControlEvent event = eventRepository.findByIdForUpdate(eventId)
                    .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
            if (event.status() != ControlEventStatus.REOPEN_REQUESTED) {
                throw ApiException.conflict("事件当前状态不允许复开确认: " + event.status());
            }
            // 与上客、检修写操作按线路行锁串行化，保证封锁与复开形成一致次序
            lineRepository.findByIdForUpdate(event.lineId())
                    .orElseThrow(() -> ApiException.notFound("线路不存在: " + event.lineId()));
            requireNoMaintenanceBlock(event.lineId());
            confirmationRepository.insertReopenConfirmation(eventId, request.role().name(), request.operatorId());
            List<String> roles = confirmationRepository.listReopenRoles(eventId);
            boolean operations = roles.contains(ReopenRole.OPERATIONS.name());
            boolean maintenance = roles.contains(ReopenRole.MAINTENANCE.name());
            ControlEventStatus status = ControlEventStatus.REOPEN_REQUESTED;
            if (operations && maintenance) {
                eventRepository.archive(eventId, Instant.now());
                lineRepository.updateStatus(event.lineId(), LineStatus.NORMAL);
                status = ControlEventStatus.RESUMED;
            }
            return json.ok(new ReopenConfirmationResponse(eventId, status.name(),
                    operations, maintenance, status == ControlEventStatus.RESUMED));
        });
    }

    /**
     * 查询线路视图（含进行中事件 id 与检修阻断状态，两类原因分别展示）。
     */
    public LineView lineView(long lineId) {
        WindLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
        Long activeEventId = eventRepository.findInProgressByLine(lineId)
                .map(ControlEvent::id).orElse(null);
        Long maintenanceOrderId = maintenanceRepository.findBlockingOrder(lineId, timeProvider.now())
                .map(MaintenanceOrder::id).orElse(null);
        return new LineView(line.id(), line.code(), line.name(), line.status().name(), activeEventId,
                maintenanceOrderId != null, maintenanceOrderId);
    }

    /**
     * 查询管制事件视图（含救援清空进度与复开岗位确认情况）。
     */
    public EventView eventView(long eventId) {
        ControlEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
        int totalSegments = segmentRepository.findByLineId(event.lineId()).size();
        int clearedSegments = confirmationRepository.countClearedSegments(eventId);
        List<String> roles = confirmationRepository.listReopenRoles(eventId);
        return new EventView(event.id(), event.lineId(), event.type().name(), event.status().name(),
                event.triggerSegmentId(), event.avgSpeed(), event.triggeredAt(), event.archivedAt(),
                clearedSegments, totalSegments, roles);
    }

    private ControlEvent requireEvent(long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
    }

    private WindSegment requireSegment(ControlEvent event, long segmentId) {
        WindSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("分段不存在: " + segmentId));
        if (segment.lineId() != event.lineId()) {
            throw ApiException.unprocessable("分段不属于事件所在线路: segmentId=" + segmentId);
        }
        return segment;
    }

    /**
     * 检修封锁检查：当前时刻存在已生效检修单即拒绝（到 end 未验收仍封锁，不自动放行）。
     */
    private void requireNoMaintenanceBlock(long lineId) {
        maintenanceRepository.findBlockingOrder(lineId, timeProvider.now())
                .ifPresent(order -> {
                    throw ApiException.conflict("检修封锁中禁止上客或复开: maintenanceOrderId=" + order.id());
                });
    }
}
