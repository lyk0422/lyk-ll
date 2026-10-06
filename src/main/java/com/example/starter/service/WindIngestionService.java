package com.example.starter.service;

import com.example.starter.model.ControlEventStatus;
import com.example.starter.model.ControlEventType;
import com.example.starter.model.LineStatus;
import com.example.starter.model.WindLine;
import com.example.starter.model.WindReading;
import com.example.starter.model.WindSegment;
import com.example.starter.model.WindStation;
import com.example.starter.repo.ControlEventRepository;
import com.example.starter.repo.LateDataEventRepository;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.StationRepository;
import com.example.starter.repo.WindReadingRepository;
import com.example.starter.web.dto.TriggeredEventView;
import com.example.starter.web.dto.WindReadingRequest;
import com.example.starter.web.dto.WindReadingResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 风速数据接入与管制判定：任一分段 10 分钟平均风速恰达 17m/s 触发缓停，
 * 恰达 20m/s 触发紧急停车，恰等阈值按高档处理；判定须恰满 10 分钟。
 */
@Service
public class WindIngestionService {

    /** 判定窗口时长：10 分钟 */
    static final Duration JUDGE_WINDOW = Duration.ofMinutes(10);
    /** 缓停阈值（m/s），恰达即触发 */
    static final double SLOW_STOP_THRESHOLD_MS = 17.0;
    /** 紧急停车阈值（m/s），恰达即触发 */
    static final double EMERGENCY_THRESHOLD_MS = 20.0;

    private static final String ENDPOINT = "wind-reading";

    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final SegmentRepository segmentRepository;
    private final WindReadingRepository readingRepository;
    private final ControlEventRepository eventRepository;
    private final LateDataEventRepository lateDataEventRepository;
    private final IdempotencyService idempotencyService;
    private final JsonSupport json;

    public WindIngestionService(LineRepository lineRepository, StationRepository stationRepository,
                                SegmentRepository segmentRepository, WindReadingRepository readingRepository,
                                ControlEventRepository eventRepository,
                                LateDataEventRepository lateDataEventRepository,
                                IdempotencyService idempotencyService, JsonSupport json) {
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.segmentRepository = segmentRepository;
        this.readingRepository = readingRepository;
        this.eventRepository = eventRepository;
        this.lateDataEventRepository = lateDataEventRepository;
        this.idempotencyService = idempotencyService;
        this.json = json;
    }

    /**
     * 接入一条风速数据并按需触发管制判定。
     */
    public ResponseEntity<String> ingest(long lineId, WindReadingRequest request) {
        String fingerprint = IdempotencyService.fingerprint(
                request.requestId(), ENDPOINT, String.valueOf(lineId),
                String.valueOf(request.stationId()), request.measuredAt().toString(),
                request.speedMs().toString());
        return idempotencyService.execute(ENDPOINT, request.requestId(), fingerprint,
                () -> doIngest(lineId, request));
    }

    private ResponseEntity<String> doIngest(long lineId, WindReadingRequest request) {
        lineRepository.findById(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
        WindStation station = stationRepository.findById(request.stationId())
                .orElseThrow(() -> ApiException.notFound("站点不存在: " + request.stationId()));
        if (station.lineId() != lineId) {
            throw ApiException.unprocessable("站点不属于线路: stationId=" + request.stationId());
        }
        Instant measuredAt = request.measuredAt();
        double speed = request.speedMs();

        // 按站点与时刻去重：同参返回已有记录，异参 409
        var existing = readingRepository.findByStationAndTime(station.id(), measuredAt);
        if (existing.isPresent()) {
            WindReading found = existing.get();
            if (Double.compare(found.speedMs(), speed) != 0) {
                throw ApiException.conflict("同站点同时刻风速数据不一致");
            }
            return json.ok(new WindReadingResponse(found.id(), station.id(), measuredAt, speed,
                    true, found.late(), false, null));
        }

        // 迟到数据：管制事件归档为终态后到达的、不晚于归档触发时刻的数据只记迟到事件
        Instant archivedTrigger = eventRepository.latestArchivedTriggeredAt(lineId);
        if (archivedTrigger != null && !measuredAt.isAfter(archivedTrigger)) {
            long id = insertReading(lineId, station.id(), measuredAt, speed, true);
            lateDataEventRepository.insert(lineId, station.id(), measuredAt, speed,
                    "管制事件已归档，迟到数据只记录不判定");
            return json.ok(new WindReadingResponse(id, station.id(), measuredAt, speed,
                    false, true, false, null));
        }

        // 乱序到达：按时刻归位入库，不参与判定，不影响已触发判定
        Instant maxMeasuredAt = readingRepository.maxMeasuredAt(station.id());
        if (maxMeasuredAt != null && !measuredAt.isAfter(maxMeasuredAt)) {
            long id = insertReading(lineId, station.id(), measuredAt, speed, false);
            return json.ok(new WindReadingResponse(id, station.id(), measuredAt, speed,
                    false, false, true, null));
        }

        long readingId = insertReading(lineId, station.id(), measuredAt, speed, false);
        TriggeredEventView triggered = evaluate(lineId, station, measuredAt);
        return json.ok(new WindReadingResponse(readingId, station.id(), measuredAt, speed,
                false, false, false, triggered));
    }

    private long insertReading(long lineId, long stationId, Instant measuredAt, double speed, boolean late) {
        try {
            return readingRepository.insert(lineId, stationId, measuredAt, speed, late);
        } catch (DuplicateKeyException e) {
            // 并发下同站点同时刻由唯一约束兜底：同参去重，异参 409
            WindReading found = readingRepository.findByStationAndTime(stationId, measuredAt)
                    .orElseThrow(() -> e);
            if (Double.compare(found.speedMs(), speed) != 0) {
                throw ApiException.conflict("同站点同时刻风速数据不一致");
            }
            return found.id();
        }
    }

    private TriggeredEventView evaluate(long lineId, WindStation station, Instant measuredAt) {
        for (WindSegment segment : segmentRepository.findByStation(lineId, station.id())) {
            Instant windowStart = measuredAt.minus(JUDGE_WINDOW);
            // 判定须恰满 10 分钟：窗口两端站点都必须有不晚于窗口起点的数据
            Instant minFrom = readingRepository.minMeasuredAtInWindow(
                    segment.fromStationId(), windowStart, measuredAt);
            Instant minTo = readingRepository.minMeasuredAtInWindow(
                    segment.toStationId(), windowStart, measuredAt);
            if (minFrom == null || minTo == null
                    || minFrom.isAfter(windowStart) || minTo.isAfter(windowStart)) {
                continue;
            }
            Double avg = readingRepository.avgSpeedInWindow(
                    segment.fromStationId(), segment.toStationId(), windowStart, measuredAt);
            if (avg == null) {
                continue;
            }
            ControlEventType type;
            if (avg >= EMERGENCY_THRESHOLD_MS) {
                type = ControlEventType.EMERGENCY_STOP;
            } else if (avg >= SLOW_STOP_THRESHOLD_MS) {
                type = ControlEventType.SLOW_STOP;
            } else {
                continue;
            }
            TriggeredEventView view = tryTrigger(lineId, segment, type, avg, measuredAt);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private TriggeredEventView tryTrigger(long lineId, WindSegment segment, ControlEventType type,
                                          double avg, Instant measuredAt) {
        // 行锁串行化线路级判定：同线路同时刻只允许一个进行中管制事件
        WindLine line = lineRepository.findByIdForUpdate(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
        if (line.status() != LineStatus.NORMAL
                || eventRepository.findInProgressByLine(lineId).isPresent()) {
            return null;
        }
        long eventId = eventRepository.insert(lineId, type, segment.id(), avg, measuredAt);
        lineRepository.updateStatus(lineId,
                type == ControlEventType.EMERGENCY_STOP ? LineStatus.EMERGENCY_STOP : LineStatus.SLOW_STOP);
        return new TriggeredEventView(eventId, type.name(), ControlEventStatus.ACTIVE.name(),
                segment.id(), avg, measuredAt);
    }
}
