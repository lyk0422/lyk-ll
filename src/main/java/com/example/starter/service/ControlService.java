package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.RescueConfirmationRequest;
import com.example.starter.api.dto.ReopenConfirmationRequest;
import com.example.starter.api.dto.ReopenRequestBody;
import com.example.starter.api.dto.SegmentReleaseRequest;
import com.example.starter.domain.ControlEventStatus;
import com.example.starter.domain.ControlEventType;
import com.example.starter.domain.LineStatus;
import com.example.starter.domain.ReopenRole;
import com.example.starter.repo.ControlEventRepository;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.ReopenRepository;
import com.example.starter.repo.RescueConfirmationRepository;
import com.example.starter.repo.SegmentReleaseRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.WindReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管制事件操作：缓停逐段放行、紧急停车救援双人确认、复开申请与双岗确认。
 */
@Service
public class ControlService {

    /** 复开风速上限（不含），单位 m/s。 */
    public static final BigDecimal REOPEN_THRESHOLD = new BigDecimal("15");
    /** 复开所需低风速持续时长（分钟），恰满即可申请。 */
    public static final int REOPEN_MINUTES = 15;

    private final LineRepository lineRepository;
    private final SegmentRepository segmentRepository;
    private final ControlEventRepository controlEventRepository;
    private final SegmentReleaseRepository segmentReleaseRepository;
    private final RescueConfirmationRepository rescueConfirmationRepository;
    private final ReopenRepository reopenRepository;
    private final WindReadingRepository windReadingRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ControlService(LineRepository lineRepository, SegmentRepository segmentRepository,
                          ControlEventRepository controlEventRepository,
                          SegmentReleaseRepository segmentReleaseRepository,
                          RescueConfirmationRepository rescueConfirmationRepository,
                          ReopenRepository reopenRepository,
                          WindReadingRepository windReadingRepository,
                          IdempotencyService idempotencyService, ObjectMapper objectMapper, Clock clock) {
        this.lineRepository = lineRepository;
        this.segmentRepository = segmentRepository;
        this.controlEventRepository = controlEventRepository;
        this.segmentReleaseRepository = segmentReleaseRepository;
        this.rescueConfirmationRepository = rescueConfirmationRepository;
        this.reopenRepository = reopenRepository;
        this.windReadingRepository = windReadingRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 缓停期间在途吊厢按站点逐段放行：仅允许放行下一个未放行分段。
     */
    @Transactional
    public IdempotencyService.Result releaseSegment(long eventId, SegmentReleaseRequest request) {
        var event = lockEvent(eventId);
        var segment = segmentRepository.findById(request.segmentId())
                .filter(s -> s.lineId() == event.lineId())
                .orElseThrow(() -> ApiException.notFound("分段不存在或不属于该线路: " + request.segmentId()));
        return idempotencyService.execute("POST /api/events/" + eventId + "/segment-releases",
                request.requestId(), Map.of("segmentId", String.valueOf(request.segmentId())),
                () -> {
                    if (event.eventType() != ControlEventType.SOFT_HOLD || event.status() != ControlEventStatus.ACTIVE) {
                        throw ApiException.unprocessable("EVENT_STATE_INVALID", "仅进行中的缓停事件允许分段放行");
                    }
                    int nextSeq = segmentReleaseRepository.maxSeqByEventId(eventId) + 1;
                    if (segment.seq() != nextSeq) {
                        throw ApiException.unprocessable("RELEASE_OUT_OF_ORDER",
                                "须按站点顺序逐段放行，下一个应放行分段顺序号为 " + nextSeq);
                    }
                    LocalDateTime now = LocalDateTime.now(clock);
                    segmentReleaseRepository.insert(eventId, segment.id(), segment.seq(), now);
                    int total = segmentRepository.findByLineId(event.lineId()).size();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("eventId", eventId);
                    body.put("releasedSegmentId", segment.id());
                    body.put("releasedSegmentSeq", segment.seq());
                    body.put("releasedCount", nextSeq);
                    body.put("totalSegments", total);
                    body.put("allReleased", nextSeq == total);
                    return new IdempotencyService.Result(200, toJson(body));
                });
    }

    /**
     * 紧急停车救援确认：同一分段须两名不同人员确认方视为清空，单人确认不生效。
     */
    @Transactional
    public IdempotencyService.Result confirmRescue(long eventId, RescueConfirmationRequest request) {
        var event = lockEvent(eventId);
        var segment = segmentRepository.findById(request.segmentId())
                .filter(s -> s.lineId() == event.lineId())
                .orElseThrow(() -> ApiException.notFound("分段不存在或不属于该线路: " + request.segmentId()));
        return idempotencyService.execute("POST /api/events/" + eventId + "/rescue-confirmations",
                request.requestId(),
                Map.of("segmentId", String.valueOf(request.segmentId()), "operator", request.operator()),
                () -> {
                    if (event.eventType() != ControlEventType.EMERGENCY_STOP
                            || event.status() != ControlEventStatus.ACTIVE) {
                        throw ApiException.unprocessable("EVENT_STATE_INVALID", "仅进行中的紧急停车事件允许救援确认");
                    }
                    LocalDateTime now = LocalDateTime.now(clock);
                    boolean inserted = rescueConfirmationRepository.insertIfAbsent(
                            eventId, segment.id(), request.operator(), now);
                    if (!inserted) {
                        throw ApiException.unprocessable("DUPLICATE_CONFIRMATION",
                                "同一人员不可重复确认同一分段");
                    }
                    int confirmed = rescueConfirmationRepository.countDistinctOperators(eventId, segment.id());
                    int total = segmentRepository.findByLineId(event.lineId()).size();
                    int cleared = rescueConfirmationRepository.findClearedSegmentIds(eventId).size();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("eventId", eventId);
                    body.put("segmentId", segment.id());
                    body.put("distinctOperators", confirmed);
                    body.put("segmentCleared", confirmed >= 2);
                    body.put("clearedSegments", cleared);
                    body.put("totalSegments", total);
                    body.put("rescueComplete", cleared == total);
                    return new IdempotencyService.Result(200, toJson(body));
                });
    }

    /**
     * 申请复开：风速回落至 15m/s 以下并持续恰满 15 分钟方可申请；
     * 紧急停车事件须先完成全部分段的双人确认。
     */
    @Transactional
    public IdempotencyService.Result requestReopen(long eventId, ReopenRequestBody request) {
        var event = lockEvent(eventId);
        return idempotencyService.execute("POST /api/events/" + eventId + "/reopen-requests",
                request.requestId(), Map.of(), () -> {
                    if (event.status() != ControlEventStatus.ACTIVE) {
                        throw ApiException.unprocessable("EVENT_STATE_INVALID", "仅进行中的事件允许申请复开");
                    }
                    var segments = segmentRepository.findByLineId(event.lineId());
                    if (event.eventType() == ControlEventType.EMERGENCY_STOP) {
                        int cleared = rescueConfirmationRepository.findClearedSegmentIds(eventId).size();
                        if (cleared < segments.size()) {
                            throw ApiException.unprocessable("RESCUE_INCOMPLETE",
                                    "救援未完成：各分段须双人确认清空后方可申请复开");
                        }
                    }
                    checkWindRecovered(segments);
                    LocalDateTime now = LocalDateTime.now(clock);
                    long requestId = reopenRepository.insertRequest(eventId, now);
                    controlEventRepository.updateStatus(eventId, ControlEventStatus.REOPEN_REQUESTED, null);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("reopenRequestId", requestId);
                    body.put("eventId", eventId);
                    body.put("status", "PENDING");
                    body.put("message", "复开申请已受理，待运营与机务双岗确认");
                    return new IdempotencyService.Result(201, toJson(body));
                });
    }

    /**
     * 复开双岗确认：运营与机务各确认一次后线路复开，事件归档为终态。
     */
    @Transactional
    public IdempotencyService.Result confirmReopen(long reopenRequestId, ReopenConfirmationRequest request) {
        var reopenRequest = reopenRepository.findRequestById(reopenRequestId)
                .orElseThrow(() -> ApiException.notFound("复开申请不存在: " + reopenRequestId));
        var event = lockEvent(reopenRequest.eventId());
        return idempotencyService.execute("POST /api/reopen-requests/" + reopenRequestId + "/confirmations",
                request.requestId(), Map.of("role", request.role().name()),
                () -> {
                    if (!"PENDING".equals(reopenRequest.status())) {
                        throw ApiException.unprocessable("REOPEN_STATE_INVALID", "复开申请不在待确认状态");
                    }
                    LocalDateTime now = LocalDateTime.now(clock);
                    boolean inserted = reopenRepository.insertConfirmationIfAbsent(
                            reopenRequestId, request.role(), now);
                    if (!inserted) {
                        throw ApiException.unprocessable("DUPLICATE_CONFIRMATION", "同一岗位不可重复确认");
                    }
                    List<ReopenRole> roles = reopenRepository.findConfirmedRoles(reopenRequestId);
                    boolean approved = roles.contains(ReopenRole.OPERATIONS) && roles.contains(ReopenRole.MAINTENANCE);
                    if (approved) {
                        reopenRepository.markApproved(reopenRequestId);
                        controlEventRepository.updateStatus(event.id(), ControlEventStatus.CLOSED, now);
                        lineRepository.updateStatus(event.lineId(), LineStatus.NORMAL);
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("reopenRequestId", reopenRequestId);
                    body.put("eventId", event.id());
                    body.put("confirmedRoles", roles.stream().map(ReopenRole::name).toList());
                    body.put("approved", approved);
                    body.put("eventStatus", approved ? ControlEventStatus.CLOSED.name() : event.status().name());
                    return new IdempotencyService.Result(200, toJson(body));
                });
    }

    /** 线路详情（含进行中的管制事件与分段列表）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> lineDetail(long lineId) {
        var line = lineRepository.findById(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", line.id());
        body.put("code", line.code());
        body.put("name", line.name());
        body.put("status", line.status().name());
        body.put("boardingAllowed", line.status() == LineStatus.NORMAL);
        body.put("segments", segmentRepository.findByLineId(lineId).stream()
                .map(s -> {
                    Map<String, Object> seg = new LinkedHashMap<String, Object>();
                    seg.put("id", s.id());
                    seg.put("seq", s.seq());
                    seg.put("name", s.name());
                    return seg;
                }).toList());
        controlEventRepository.findOpenByLineId(lineId).ifPresentOrElse(
                e -> body.put("openEvent", eventBody(e)),
                () -> body.put("openEvent", null));
        return body;
    }

    /** 管制事件详情。 */
    @Transactional(readOnly = true)
    public Map<String, Object> eventDetail(long eventId) {
        var event = controlEventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
        Map<String, Object> body = eventBody(event);
        body.put("releases", segmentReleaseRepository.findByEventId(eventId).stream()
                .map(r -> {
                    Map<String, Object> rel = new LinkedHashMap<String, Object>();
                    rel.put("segmentId", r.segmentId());
                    rel.put("segmentSeq", r.segmentSeq());
                    rel.put("releasedAt", r.releasedAt().toString());
                    return rel;
                }).toList());
        body.put("clearedSegmentIds", rescueConfirmationRepository.findClearedSegmentIds(eventId));
        reopenRepository.findRequestByEventId(eventId).ifPresentOrElse(r -> {
            Map<String, Object> reopen = new LinkedHashMap<String, Object>();
            reopen.put("id", r.id());
            reopen.put("status", r.status());
            reopen.put("requestedAt", r.requestedAt().toString());
            reopen.put("confirmedRoles",
                    reopenRepository.findConfirmedRoles(r.id()).stream().map(ReopenRole::name).toList());
            body.put("reopenRequest", reopen);
        }, () -> body.put("reopenRequest", null));
        return body;
    }

    /** 校验线路所有分段最近恰满 15 个分钟桶的平均风速均低于 15m/s。 */
    private void checkWindRecovered(List<SegmentRepository.SegmentRow> segments) {
        for (var segment : segments) {
            var latest = windReadingRepository.latestBucket(segment.id())
                    .orElseThrow(() -> ApiException.unprocessable("WIND_NOT_RECOVERED",
                            "分段 " + segment.id() + " 无风速数据，不满足复开条件"));
            LocalDateTime windowStart = latest.minusMinutes(REOPEN_MINUTES - 1L);
            var stats = windReadingRepository.bucketStats(segment.id(), windowStart, latest);
            if (stats.size() < REOPEN_MINUTES) {
                throw ApiException.unprocessable("WIND_NOT_RECOVERED",
                        "分段 " + segment.id() + " 低风速持续不足 15 分钟（恰满 15 分钟方可申请）");
            }
            boolean allBelow = stats.stream()
                    .allMatch(s -> s.avgSpeed().compareTo(REOPEN_THRESHOLD) < 0);
            if (!allBelow) {
                throw ApiException.unprocessable("WIND_NOT_RECOVERED",
                        "分段 " + segment.id() + " 最近 15 分钟内存在不低于 15m/s 的风速");
            }
        }
    }

    private ControlEventRepository.ControlEventRow lockEvent(long eventId) {
        var event = controlEventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("管制事件不存在: " + eventId));
        // 锁住线路行，串行化同线路管制操作
        lineRepository.lockById(event.lineId());
        return event;
    }

    private Map<String, Object> eventBody(ControlEventRepository.ControlEventRow event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id());
        body.put("lineId", event.lineId());
        body.put("eventType", event.eventType().name());
        body.put("status", event.status().name());
        body.put("triggerSegmentId", event.triggerSegmentId());
        body.put("triggerAvgSpeed", event.triggerAvgSpeed());
        body.put("windowStart", event.windowStart().toString());
        body.put("windowEnd", event.windowEnd().toString());
        body.put("createdAt", event.createdAt().toString());
        body.put("closedAt", event.closedAt() == null ? null : event.closedAt().toString());
        return body;
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
