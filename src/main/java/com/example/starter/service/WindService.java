package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.WindReadingRequest;
import com.example.starter.domain.ControlEventStatus;
import com.example.starter.domain.ControlEventType;
import com.example.starter.domain.LineStatus;
import com.example.starter.repo.ControlEventRepository;
import com.example.starter.repo.LateWindEventRepository;
import com.example.starter.repo.LineRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.WindReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 风速数据接入与管制判定。
 *
 * <p>判定规则：任一分段恰满 10 分钟（10 个分钟桶）平均风速达到 17m/s（含）触发缓停，
 * 达到 20m/s（含）触发紧急停车，恰等阈值按高档处理；乱序数据按时刻归位后补齐窗口同样参与判定；
 * 同一线路同一时刻仅允许一个进行中管制事件；归档事件窗口内的迟到数据只记迟到事件。
 */
@Service
public class WindService {

    /** 缓停阈值（含），单位 m/s。 */
    public static final BigDecimal SOFT_THRESHOLD = new BigDecimal("17");
    /** 紧急停车阈值（含），单位 m/s。 */
    public static final BigDecimal EMERGENCY_THRESHOLD = new BigDecimal("20");
    /** 判定窗口长度（分钟），须恰满才触发。 */
    public static final int WINDOW_MINUTES = 10;

    private final LineRepository lineRepository;
    private final SegmentRepository segmentRepository;
    private final WindReadingRepository windReadingRepository;
    private final ControlEventRepository controlEventRepository;
    private final LateWindEventRepository lateWindEventRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WindService(LineRepository lineRepository, SegmentRepository segmentRepository,
                       WindReadingRepository windReadingRepository,
                       ControlEventRepository controlEventRepository,
                       LateWindEventRepository lateWindEventRepository,
                       IdempotencyService idempotencyService, ObjectMapper objectMapper, Clock clock) {
        this.lineRepository = lineRepository;
        this.segmentRepository = segmentRepository;
        this.windReadingRepository = windReadingRepository;
        this.controlEventRepository = controlEventRepository;
        this.lateWindEventRepository = lateWindEventRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 上报一条风速数据并按需触发管制判定。
     */
    @Transactional
    public IdempotencyService.Result ingest(long lineId, WindReadingRequest request) {
        // 行锁串行化同线路的判定与事件创建，保证同线路仅一个进行中事件
        var line = lineRepository.lockById(lineId)
                .orElseThrow(() -> ApiException.notFound("线路不存在: " + lineId));
        var segment = segmentRepository.findById(request.segmentId())
                .filter(s -> s.lineId() == lineId)
                .orElseThrow(() -> ApiException.notFound("分段不存在或不属于该线路: " + request.segmentId()));

        Map<String, String> fields = Map.of(
                "segmentId", String.valueOf(request.segmentId()),
                "measuredAt", request.measuredAt().toString(),
                "speed", request.speed().toPlainString());
        return idempotencyService.execute("POST /api/lines/" + lineId + "/wind-readings",
                request.requestId(), fields,
                () -> doIngest(line, segment, request));
    }

    private IdempotencyService.Result doIngest(LineRepository.LineRow line,
                                               SegmentRepository.SegmentRow segment,
                                               WindReadingRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime bucket = request.measuredAt().truncatedTo(ChronoUnit.MINUTES);

        // 1. 按分段（站点）与时刻去重：同键数据直接返回已存记录
        var duplicated = windReadingRepository.findBySegmentAndMeasuredAt(segment.id(), request.measuredAt());
        if (duplicated.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accepted", false);
            body.put("duplicate", true);
            body.put("readingId", duplicated.get().id());
            body.put("message", "同分段同时刻数据已存在，按去重规则忽略");
            return new IdempotencyService.Result(200, toJson(body));
        }

        // 2. 迟到判定：测量时刻落在已判定窗口（含进行中/已归档事件）之内或之前，只记迟到事件
        var maxWindowEnd = controlEventRepository.maxWindowEndByLineId(line.id());
        if (maxWindowEnd.isPresent() && !bucket.isAfter(maxWindowEnd.get())) {
            lateWindEventRepository.insert(line.id(), segment.id(), request.measuredAt(), request.speed(), now,
                    "测量时刻不晚于已判定窗口结束分钟 " + maxWindowEnd.get() + "，按迟到数据处理");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accepted", false);
            body.put("late", true);
            body.put("message", "迟到风速数据，仅记录迟到事件，不参与判定");
            return new IdempotencyService.Result(200, toJson(body));
        }

        // 3. 正常入库（乱序数据按测量时刻归位）
        windReadingRepository.insert(line.id(), segment.id(), request.measuredAt(), bucket, request.speed(), now);

        // 4. 仅当线路无进行中管制事件时执行触发判定；管制期间数据照常入库但不影响已触发判定
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("duplicate", false);
        body.put("late", false);
        if (line.status() == LineStatus.NORMAL) {
            var triggered = evaluate(line, segment, bucket, maxWindowEnd.orElse(null), now);
            if (triggered != null) {
                body.put("triggeredEvent", triggered);
            }
        }
        return new IdempotencyService.Result(200, toJson(body));
    }

    /**
     * 以新数据所在分钟桶为基准，检查所有可能因该数据补齐的 10 分钟窗口；
     * 命中最早一个恰满窗口即触发，恰等阈值按高档（紧急停车优先）处理。
     */
    private Map<String, Object> evaluate(LineRepository.LineRow line, SegmentRepository.SegmentRow segment,
                                         LocalDateTime bucket, LocalDateTime adjudicatedEnd, LocalDateTime now) {
        for (int offset = 0; offset < WINDOW_MINUTES; offset++) {
            LocalDateTime windowEnd = bucket.plusMinutes(offset);
            LocalDateTime windowStart = windowEnd.minusMinutes(WINDOW_MINUTES - 1L);
            if (adjudicatedEnd != null && !windowStart.isAfter(adjudicatedEnd)) {
                continue; // 窗口与已判定期间重叠，不重复判定
            }
            List<WindReadingRepository.BucketStat> stats =
                    windReadingRepository.bucketStats(segment.id(), windowStart, windowEnd);
            if (stats.size() < WINDOW_MINUTES) {
                continue; // 未恰满 10 分钟，不触发
            }
            BigDecimal sum = stats.stream()
                    .map(WindReadingRepository.BucketStat::avgSpeed)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(WINDOW_MINUTES), 2, RoundingMode.HALF_UP);
            ControlEventType type = null;
            if (avg.compareTo(EMERGENCY_THRESHOLD) >= 0) {
                type = ControlEventType.EMERGENCY_STOP;
            } else if (avg.compareTo(SOFT_THRESHOLD) >= 0) {
                type = ControlEventType.SOFT_HOLD;
            }
            if (type == null) {
                continue;
            }
            long eventId = controlEventRepository.insert(line.id(), type, ControlEventStatus.ACTIVE,
                    segment.id(), avg, windowStart, windowEnd, now);
            lineRepository.updateStatus(line.id(),
                    type == ControlEventType.EMERGENCY_STOP ? LineStatus.EMERGENCY_STOP : LineStatus.SOFT_HOLD);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", eventId);
            event.put("eventType", type.name());
            event.put("status", ControlEventStatus.ACTIVE.name());
            event.put("triggerSegmentId", segment.id());
            event.put("triggerAvgSpeed", avg);
            event.put("windowStart", windowStart.toString());
            event.put("windowEnd", windowEnd.toString());
            return event;
        }
        return null;
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
