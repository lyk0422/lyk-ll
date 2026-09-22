package com.example.starter.perception.service;

import com.example.starter.perception.api.AssociationRequest;
import com.example.starter.perception.api.AssociationResponse;
import com.example.starter.perception.api.DetectionRequest;
import com.example.starter.perception.api.DetectionResult;
import com.example.starter.perception.api.TrackSnapshotResponse;
import com.example.starter.perception.config.TimeService;
import com.example.starter.perception.config.TrackProperties;
import com.example.starter.perception.domain.StateMachine;
import com.example.starter.perception.domain.Track;
import com.example.starter.perception.domain.TrackEvent;
import com.example.starter.perception.domain.TrackState;
import com.example.starter.perception.error.BusinessRuleException;
import com.example.starter.perception.error.TrackNotFoundException;
import com.example.starter.perception.error.VersionConflictException;
import com.example.starter.perception.repository.ProcessedDetectionRepository;
import com.example.starter.perception.repository.TrackEventRepository;
import com.example.starter.perception.repository.TrackRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 检测关联与轨迹状态机核心服务。
 *
 * <p>不变量：
 * <ul>
 *   <li>重复 detectionId 幂等返回首次结果，绝不重复应用、重复建轨；</li>
 *   <li>低置信度、乱序检测被拒绝，轨迹版本/最近观测/状态不发生任何变化；</li>
 *   <li>所有有效变化（含遮挡/丢失生命周期）按 (event_time, id) 全序写入 H2 事件流；</li>
 *   <li>每次有效变化版本严格加1，状态迁移必须经过 {@link StateMachine} 校验；</li>
 *   <li>期望版本不匹配或并发 CAS 失败时整批回滚。</li>
 * </ul>
 */
@Service
public class TrackService {

    private static final String EVENT_CREATED = "TRACK_CREATED";
    private static final String EVENT_ASSOCIATED = "ASSOCIATED";
    private static final String EVENT_STATE_CHANGED = "STATE_CHANGED";
    private static final String EVENT_REIDENTIFIED = "REIDENTIFIED";
    private static final String EVENT_RECYCLED = "RECYCLED";

    private static final List<TrackState> CANDIDATE_STATES = List.of(
            TrackState.CREATED, TrackState.TRACKING, TrackState.OCCLUDED,
            TrackState.LOST, TrackState.REIDENTIFIED);

    private final TrackRepository trackRepository;
    private final TrackEventRepository eventRepository;
    private final ProcessedDetectionRepository processedDetectionRepository;
    private final TrackProperties properties;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;

    public TrackService(TrackRepository trackRepository,
                        TrackEventRepository eventRepository,
                        ProcessedDetectionRepository processedDetectionRepository,
                        TrackProperties properties,
                        TimeService timeService,
                        ObjectMapper objectMapper) {
        this.trackRepository = trackRepository;
        this.eventRepository = eventRepository;
        this.processedDetectionRepository = processedDetectionRepository;
        this.properties = properties;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单个事务内原子处理一批检测：按（时戳, detectionId）确定顺序，保证重放与并发下结果确定。
     */
    @Transactional
    public AssociationResponse associate(AssociationRequest request) {
        Instant processedAt = timeService.now();

        // 乐观锁：整批开始前校验调用方声明的期望版本，任一不匹配则整批拒绝。
        if (request.expectedVersions() != null) {
            for (Map.Entry<String, Long> expectation : request.expectedVersions().entrySet()) {
                Track current = trackRepository.findById(expectation.getKey())
                        .orElseThrow(() -> new VersionConflictException(
                                "track not found for expected version: " + expectation.getKey()));
                if (current.getVersion() != expectation.getValue()) {
                    throw new VersionConflictException("version conflict for track " + expectation.getKey()
                            + ": expected=" + expectation.getValue() + ", actual=" + current.getVersion());
                }
            }
        }

        List<DetectionRequest> ordered = new ArrayList<>(request.detections());
        ordered.sort(Comparator.comparing(DetectionRequest::timestamp)
                .thenComparing(DetectionRequest::detectionId));

        List<DetectionResult> results = new ArrayList<>(ordered.size());
        int acceptedCount = 0;
        int rejectedCount = 0;
        Instant latestAcceptedTimestamp = null;
        for (DetectionRequest detection : ordered) {
            DetectionResult result = processOne(detection, processedAt);
            results.add(result);
            if (result.accepted()) {
                acceptedCount++;
                latestAcceptedTimestamp = detection.timestamp();
            } else {
                rejectedCount++;
            }
        }

        // 批末补账：本批没有观测命中、但按门限已经超时的轨迹同样是有效变化，
        // 必须把 OCCLUDED/LOST 迁移写入事件流，保证任意时刻重放都能还原全部轨迹状态。
        // 参考时刻取本批最晚的"已接受"检测时戳，低置信度/乱序拒绝不得推动任何状态。
        if (latestAcceptedTimestamp != null) {
            materializeTimeouts(latestAcceptedTimestamp, processedAt);
        }
        return new AssociationResponse(results, acceptedCount, rejectedCount, processedAt);
    }

    /**
     * 为全部 CREATED/TRACKING/OCCLUDED 轨迹按参考时刻补齐遮挡/丢失迁移；
     * RECYCLED/LOST/REIDENTIFIED 状态不参与。
     */
    private void materializeTimeouts(Instant referenceTime, Instant now) {
        List<Track> tracks = trackRepository.findByStates(List.of(
                TrackState.CREATED.name(), TrackState.TRACKING.name(), TrackState.OCCLUDED.name(),
                TrackState.REIDENTIFIED.name()));
        for (Track track : tracks) {
            materializeLifecycle(track, referenceTime, null, now);
        }
    }

    private DetectionResult processOne(DetectionRequest detection, Instant processedAt) {
        // 1) 幂等：重复 detectionId 原样返回首次处理结果。
        Optional<String> firstResult = processedDetectionRepository.findResultById(detection.detectionId());
        if (firstResult.isPresent()) {
            DetectionResult first = fromJson(firstResult.get(), DetectionResult.class);
            return DetectionResult.duplicate(detection.detectionId(), first);
        }

        // 2) 低置信度拒绝：不产生任何事件或轨迹变化，仅记录首次处理结论。
        if (detection.confidence() < properties.getConfidenceThreshold()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("decision", "REJECTED");
            evidence.put("reason", "LOW_CONFIDENCE");
            evidence.put("confidence", detection.confidence());
            evidence.put("threshold", properties.getConfidenceThreshold());
            return persistRejected(detection, "LOW_CONFIDENCE", evidence, processedAt);
        }

        // 3) 关联候选：同类、未回收轨迹；同时构造可解释证据。
        List<Track> candidates = trackRepository
                .findByCategoryAndStates(detection.category(), stateNames(CANDIDATE_STATES));
        // 候选已按 track_id 升序返回，严格取最近距离即可在等距时天然按轨迹ID决胜，保证确定性。
        Track nearestActive = null;
        Track nearestLost = null;
        double nearestActiveDistance = Double.POSITIVE_INFINITY;
        double nearestLostDistance = Double.POSITIVE_INFINITY;
        List<Map<String, Object>> candidateEvidence = new ArrayList<>();

        for (Track candidate : candidates) {
            double distance = distance(candidate, detection);
            double gate = candidate.getState() == TrackState.LOST
                    ? properties.getReidentificationGateMeter()
                    : properties.getAssociationGateMeter();
            boolean within = distance <= gate;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trackId", candidate.getTrackId());
            item.put("state", candidate.getState().name());
            item.put("distanceMeter", round(distance));
            item.put("gateMeter", gate);
            item.put("withinGate", within);
            item.put("lastObservedAt", candidate.getLastObservedAt().toString());
            candidateEvidence.add(item);

            if (!within) {
                continue;
            }
            if (candidate.getState() == TrackState.LOST) {
                if (distance < nearestLostDistance) {
                    nearestLostDistance = distance;
                    nearestLost = candidate;
                }
            } else if (distance < nearestActiveDistance) {
                nearestActiveDistance = distance;
                nearestActive = candidate;
            }
        }

        // 4) 乱序拒绝：检测落在某条在门限内、但已观测过更晚时戳的轨迹上，
        //    关联它会让最近观测/状态回退，故拒绝；远离全部既有轨迹的旧检测允许另建新轨（不造成回退）。
        Track chosen = nearestActive != null ? nearestActive : nearestLost;
        if (chosen != null && detection.timestamp().isBefore(chosen.getLastObservedAt())) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("decision", "REJECTED");
            evidence.put("reason", "OUT_OF_ORDER");
            evidence.put("candidateTrackId", chosen.getTrackId());
            evidence.put("candidateLastObservedAt", chosen.getLastObservedAt().toString());
            evidence.put("detectionTimestamp", detection.timestamp().toString());
            evidence.put("candidates", candidateEvidence);
            return persistRejected(detection, "OUT_OF_ORDER", evidence, processedAt);
        }

        if (chosen == null) {
            return createTrack(detection, candidateEvidence, processedAt);
        }
        return associateToTrack(detection, chosen, nearestActive != null, nearestActiveDistance,
                nearestLostDistance, candidateEvidence, processedAt);
    }

    private DetectionResult createTrack(DetectionRequest detection,
                                        List<Map<String, Object>> candidateEvidence,
                                        Instant processedAt) {
        Instant now = timeService.now();
        Track track = new Track();
        track.setTrackId(String.format("TRK-%08d", trackRepository.nextTrackNumber()));
        track.setCategory(detection.category());
        track.setState(TrackState.CREATED);
        track.setConfidence(detection.confidence());
        track.setPosX(detection.x());
        track.setPosY(detection.y());
        track.setLastObservedAt(detection.timestamp());
        track.setVersion(1);
        track.setCreatedAt(now);
        track.setUpdatedAt(now);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", "CREATE");
        evidence.put("reason", "NO_CANDIDATE_WITHIN_GATE");
        evidence.put("source", detection.source());
        evidence.put("associationGateMeter", properties.getAssociationGateMeter());
        evidence.put("reidentificationGateMeter", properties.getReidentificationGateMeter());
        evidence.put("candidates", candidateEvidence);
        track.setEvidence(toJson(evidence));

        trackRepository.insert(track);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "CREATE");
        payload.put("category", track.getCategory());
        payload.put("x", track.getPosX());
        payload.put("y", track.getPosY());
        payload.put("confidence", track.getConfidence());
        payload.put("timestamp", detection.timestamp().toString());
        payload.put("createdAt", now.toString());
        payload.put("evidence", evidence);
        appendEvent(track, EVENT_CREATED, null, TrackState.CREATED, track.getVersion(),
                detection.detectionId(), payload, detection.timestamp(), now);

        DetectionResult result = DetectionResult.accepted(detection.detectionId(), track.getTrackId(),
                TrackState.CREATED.name(), track.getVersion(), evidence);
        persistAccepted(detection, track, result, processedAt);
        return result;
    }

    private DetectionResult associateToTrack(DetectionRequest detection, Track track, boolean activeChosen,
                                             double activeDistance, double lostDistance,
                                             List<Map<String, Object>> candidateEvidence,
                                             Instant processedAt) {
        Instant now = timeService.now();
        TrackState stateBefore = track.getState();

        // 门限驱动的生命周期补账：遮挡/丢失的时刻回填为真实超时时刻，
        // 使事件流在任意指定时刻重放都能还原 OCCLUDED/LOST。
        materializeLifecycle(track, detection.timestamp(), detection.detectionId(), now);

        // 期望版本必须在补账之后捕获：补账本身也会推进版本与数据库行版本。
        long expectedVersion = track.getVersion();
        TrackState effectiveBefore = track.getState();
        TrackState newState;
        String eventType;
        String decision;
        if (effectiveBefore == TrackState.LOST) {
            newState = TrackState.REIDENTIFIED;
            eventType = EVENT_REIDENTIFIED;
            decision = "REIDENTIFY";
        } else {
            newState = TrackState.TRACKING;
            eventType = EVENT_ASSOCIATED;
            decision = "ASSOCIATE";
            StateMachine.requireTransit(effectiveBefore, TrackState.TRACKING);
        }
        StateMachine.requireTransit(effectiveBefore, newState);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", decision);
        evidence.put("source", detection.source());
        evidence.put("distanceMeter", round(activeChosen ? activeDistance : lostDistance));
        evidence.put("gateMeter", effectiveBefore == TrackState.LOST
                ? properties.getReidentificationGateMeter()
                : properties.getAssociationGateMeter());
        evidence.put("stateBefore", stateBefore.name());
        evidence.put("stateAfterLifecycle", effectiveBefore.name());
        evidence.put("candidates", candidateEvidence);
        if (effectiveBefore == TrackState.LOST) {
            evidence.put("idSwitchPolicy", "PRESERVE_ORIGINAL_TRACK_ID");
        }

        track.setState(newState);
        track.setConfidence(detection.confidence());
        track.setPosX(detection.x());
        track.setPosY(detection.y());
        track.setLastObservedAt(detection.timestamp());
        track.setEvidence(toJson(evidence));
        track.setVersion(track.getVersion() + 1);
        track.setUpdatedAt(now);

        if (!trackRepository.compareAndUpdate(track, expectedVersion)) {
            throw new VersionConflictException("concurrent update conflict on track " + track.getTrackId()
                    + " at version " + expectedVersion);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "ASSOCIATE");
        payload.put("x", detection.x());
        payload.put("y", detection.y());
        payload.put("confidence", detection.confidence());
        payload.put("timestamp", detection.timestamp().toString());
        payload.put("evidence", evidence);
        appendEvent(track, eventType, effectiveBefore, newState, track.getVersion(),
                detection.detectionId(), payload, detection.timestamp(), now);

        DetectionResult result = DetectionResult.accepted(detection.detectionId(), track.getTrackId(),
                newState.name(), track.getVersion(), evidence);
        persistAccepted(detection, track, result, processedAt);
        return result;
    }

    /**
     * 在关联前补齐遮挡/丢失生命周期事件：
     * TRACKING/CREATED 超时刻 -&gt; OCCLUDED；再超过丢失间隔 -&gt; LOST。事件时刻回填为真实超时时刻。
     */
    private void materializeLifecycle(Track track, Instant detectionTime, String detectionId, Instant now) {
        Instant occlusionAt = track.getLastObservedAt().plusMillis(properties.getOcclusionGapMs());
        Instant lostAt = occlusionAt.plusMillis(properties.getLostGapMs());

        if ((track.getState() == TrackState.TRACKING || track.getState() == TrackState.CREATED
                || track.getState() == TrackState.REIDENTIFIED)
                && !detectionTime.isBefore(occlusionAt)) {
            TrackState from = track.getState();
            // 若连丢失时刻也已越过，丢失事件排在更后面，遮挡事件仍先落库。
            transitLifecycle(track, from, TrackState.OCCLUDED, "OCCLUSION_TIMEOUT",
                    occlusionAt, detectionTime, detectionId, now);
        }
        if (track.getState() == TrackState.OCCLUDED && !detectionTime.isBefore(lostAt)) {
            transitLifecycle(track, TrackState.OCCLUDED, TrackState.LOST, "LOST_TIMEOUT",
                    lostAt, detectionTime, detectionId, now);
        }
    }

    private void transitLifecycle(Track track, TrackState from, TrackState to, String reason,
                                  Instant eventTime, Instant detectionTime, String detectionId, Instant now) {
        StateMachine.requireTransit(from, to);
        long expectedVersion = track.getVersion();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", "LIFECYCLE");
        evidence.put("reason", reason);
        evidence.put("lastObservedAt", track.getLastObservedAt().toString());
        evidence.put("timeoutAt", eventTime.toString());
        evidence.put("triggeredByDetection", detectionId);
        evidence.put("triggerDetectionTimestamp", detectionTime.toString());
        track.setState(to);
        track.setVersion(track.getVersion() + 1);
        track.setEvidence(toJson(evidence));
        track.setUpdatedAt(now);
        if (!trackRepository.compareAndUpdate(track, expectedVersion)) {
            throw new VersionConflictException("concurrent lifecycle update conflict on track "
                    + track.getTrackId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "LIFECYCLE");
        payload.put("reason", reason);
        payload.put("timeoutAt", eventTime.toString());
        payload.put("evidence", evidence);
        appendEvent(track, EVENT_STATE_CHANGED, from, to, track.getVersion(),
                null, payload, eventTime, now);
    }

    /** 轨迹当前态快照；TRACKING/OCCLUDED 的超时状态按当前时钟派生展示（事件流在关联时补账）。 */
    @Transactional(readOnly = true)
    public TrackSnapshotResponse currentSnapshot(String trackId) {
        Track track = trackRepository.findById(trackId)
                .orElseThrow(() -> new TrackNotFoundException(trackId));
        return toSnapshot(track, deriveState(track, timeService.now()), "LIVE", timeService.now(), null);
    }

    /** 全部轨迹当前态快照。 */
    @Transactional(readOnly = true)
    public List<TrackSnapshotResponse> currentSnapshots() {
        Instant now = timeService.now();
        List<TrackSnapshotResponse> responses = new ArrayList<>();
        for (Track track : trackRepository.findAll()) {
            responses.add(toSnapshot(track, deriveState(track, now), "LIVE", now, null));
        }
        return responses;
    }

    /**
     * 从 H2 事件流重放指定轨迹截至 at（含）的快照；同一事件集合重放结果与版本确定一致。
     */
    @Transactional(readOnly = true)
    public TrackSnapshotResponse replaySnapshot(String trackId, Instant at) {
        List<TrackEvent> events = eventRepository.findByTrackUntil(trackId, at);
        if (events.isEmpty()) {
            throw new TrackNotFoundException(trackId + " at " + at);
        }
        ReplayedTrack replayed = ReplayedTrack.replay(events);
        return toReplaySnapshot(replayed, at, timeService.now());
    }

    /** 从事件流重放指定时刻全部存在过的轨迹快照。 */
    @Transactional(readOnly = true)
    public List<TrackSnapshotResponse> replaySnapshots(Instant at) {
        Map<String, List<TrackEvent>> byTrack = new LinkedHashMap<>();
        for (TrackEvent event : eventRepository.findAllUntil(at)) {
            byTrack.computeIfAbsent(event.getTrackId(), k -> new ArrayList<>()).add(event);
        }
        Instant reconstructedAt = timeService.now();
        List<TrackSnapshotResponse> responses = new ArrayList<>();
        for (List<TrackEvent> events : byTrack.values()) {
            responses.add(toReplaySnapshot(ReplayedTrack.replay(events), at, reconstructedAt));
        }
        return responses;
    }

    /** 显式回收轨迹：LOST -&gt; RECYCLED，终态不再接受观测；写入事件流。 */
    @Transactional
    public TrackSnapshotResponse recycle(String trackId, Long expectedVersion) {
        Track track = trackRepository.findById(trackId)
                .orElseThrow(() -> new TrackNotFoundException(trackId));
        if (expectedVersion != null && track.getVersion() != expectedVersion) {
            throw new VersionConflictException("version conflict for track " + trackId
                    + ": expected=" + expectedVersion + ", actual=" + track.getVersion());
        }
        Instant now = timeService.now();
        materializeLifecycle(track, now, null, now);
        if (track.getState() != TrackState.LOST) {
            throw new BusinessRuleException("ILLEGAL_TRANSITION",
                    "only LOST track can be recycled, current state: " + track.getState());
        }
        StateMachine.requireTransit(TrackState.LOST, TrackState.RECYCLED);
        long expected = track.getVersion();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", "RECYCLE");
        evidence.put("recycledAt", now.toString());
        track.setState(TrackState.RECYCLED);
        track.setVersion(track.getVersion() + 1);
        track.setEvidence(toJson(evidence));
        track.setUpdatedAt(now);
        if (!trackRepository.compareAndUpdate(track, expected)) {
            throw new VersionConflictException("concurrent update conflict on track " + trackId);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RECYCLE");
        payload.put("evidence", evidence);
        appendEvent(track, EVENT_RECYCLED, TrackState.LOST, TrackState.RECYCLED, track.getVersion(),
                null, payload, now, now);
        return toSnapshot(track, TrackState.RECYCLED, "LIVE", now, null);
    }

    private void persistAccepted(DetectionRequest detection, Track track, DetectionResult result,
                                 Instant processedAt) {
        processedDetectionRepository.insert(detection.detectionId(), track.getTrackId(), true,
                toJson(result), detection.timestamp(), processedAt);
    }

    private DetectionResult persistRejected(DetectionRequest detection, String reason,
                                            Map<String, Object> evidence, Instant processedAt) {
        DetectionResult result = DetectionResult.rejected(detection.detectionId(), reason, evidence);
        processedDetectionRepository.insert(detection.detectionId(), null, false,
                toJson(result), detection.timestamp(), processedAt);
        return result;
    }

    private void appendEvent(Track track, String eventType, TrackState from, TrackState to, long version,
                             String detectionId, Map<String, Object> payload, Instant eventTime,
                             Instant createdAt) {
        TrackEvent event = new TrackEvent();
        event.setEventId("EVT-" + UUID.randomUUID());
        event.setTrackId(track.getTrackId());
        event.setEventType(eventType);
        event.setFromState(from);
        event.setToState(to);
        event.setVersion(version);
        event.setDetectionId(detectionId);
        event.setPayload(toJson(payload));
        event.setEventTime(eventTime);
        event.setCreatedAt(createdAt);
        eventRepository.append(event);
    }

    private TrackState deriveState(Track track, Instant at) {
        if (track.getState() == TrackState.TRACKING || track.getState() == TrackState.CREATED
                || track.getState() == TrackState.REIDENTIFIED) {
            Instant occlusionAt = track.getLastObservedAt().plusMillis(properties.getOcclusionGapMs());
            Instant lostAt = occlusionAt.plusMillis(properties.getLostGapMs());
            if (!at.isBefore(lostAt)) {
                return TrackState.LOST;
            }
            if (!at.isBefore(occlusionAt)) {
                return TrackState.OCCLUDED;
            }
        } else if (track.getState() == TrackState.OCCLUDED) {
            Instant occlusionAt = track.getLastObservedAt().plusMillis(properties.getOcclusionGapMs());
            if (!at.isBefore(occlusionAt.plusMillis(properties.getLostGapMs()))) {
                return TrackState.LOST;
            }
        }
        return track.getState();
    }

    private TrackSnapshotResponse toSnapshot(Track track, TrackState effectiveState, String source,
                                             Instant reconstructedAt, Instant at) {
        return new TrackSnapshotResponse(
                track.getTrackId(),
                track.getCategory(),
                effectiveState.name(),
                track.getConfidence(),
                track.getPosX(),
                track.getPosY(),
                track.getLastObservedAt(),
                track.getVersion(),
                fromJson(track.getEvidence(), new TypeReference<Map<String, Object>>() {
                }),
                track.getCreatedAt(),
                source,
                at,
                reconstructedAt);
    }

    private TrackSnapshotResponse toReplaySnapshot(ReplayedTrack replayed, Instant at, Instant reconstructedAt) {
        return new TrackSnapshotResponse(
                replayed.trackId(),
                replayed.category(),
                replayed.state().name(),
                replayed.confidence(),
                replayed.x(),
                replayed.y(),
                replayed.lastObservedAt(),
                replayed.version(),
                replayed.evidence(),
                replayed.createdAt(),
                "REPLAYED",
                at,
                reconstructedAt);
    }

    private static List<String> stateNames(List<TrackState> states) {
        return states.stream().map(Enum::name).toList();
    }

    private static double distance(Track track, DetectionRequest detection) {
        return distance(track, detection.x(), detection.y());
    }

    private static double distance(Track track, double x, double y) {
        return Math.hypot(track.getPosX() - x, track.getPosY() - y);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize evidence", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize json", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize json", e);
        }
    }
}
