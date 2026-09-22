package com.example.starter.service;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.Detection;
import com.example.starter.domain.DetectionOutcome;
import com.example.starter.domain.EvidenceType;
import com.example.starter.domain.TrackEvent;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.domain.TrackStatus;
import com.example.starter.domain.VersionConflictException;
import com.example.starter.persistence.EventStore;
import com.example.starter.persistence.TrackRepository;
import com.example.starter.web.dto.AssociateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 轨迹关联与状态机事务内核。
 *
 * <p>所有写操作在单个数据库事务内完成：先查重/查轨迹，再做状态判定，
 * 最后以乐观锁版本号条件原子更新轨迹并追加事件流与证据。乱序检测、
 * 低置信度检测与重复 detectionId 在任何写入之前被拒绝，因此不会使
 * 轨迹版本、最近观测或状态发生任何变化。并发互斥由 {@link DetectionLock}
 * 在外观层保证，本类方法必须在持锁/代理事务内调用。</p>
 */
@Service
public class TrackLifecycleCore {

    /** 检测置信度默认门限：低于该值的检测直接拒绝。 */
    static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.5;
    /** 检测与轨迹最近观测位置的默认关联门限（米）。 */
    static final double DEFAULT_GATE_THRESHOLD = 2.0;

    private final TrackRepository trackRepository;
    private final EventStore eventStore;
    private final TrackReplayer replayer;
    private final TransactionalKeyLock keyLock;

    public TrackLifecycleCore(TrackRepository trackRepository,
                              EventStore eventStore,
                              TrackReplayer replayer,
                              TransactionalKeyLock keyLock) {
        this.trackRepository = trackRepository;
        this.eventStore = eventStore;
        this.replayer = replayer;
        this.keyLock = keyLock;
    }

    @Transactional
    public AssociateResponse associateInTx(Detection detection, String targetTrackUid,
                                           Long expectedVersion, Double gateThreshold) {
        long now = nowMillis();

        // 检测键锁持有到事务完成，保证相同 detectionId 的并发提交在 JVM 内串行。
        keyLock.lockUntilTransactionComplete("det:" + detection.detectionId());

        // 1) 重复 detectionId：幂等返回首次处理结果，不产生任何变化。
        Optional<EventStore.ProcessedDetectionRecord> previous =
                eventStore.findProcessed(detection.detectionId());
        if (previous.isPresent()) {
            return duplicateResponse(previous.get());
        }

        // 2) 低置信度：登记拒绝并返回，绝不触碰任何轨迹。
        if (detection.confidence() < DEFAULT_CONFIDENCE_THRESHOLD) {
            return reject(detection, "LOW_CONFIDENCE",
                    "置信度 " + detection.confidence() + " 低于门限 "
                            + DEFAULT_CONFIDENCE_THRESHOLD, now);
        }

        double gate = gateThreshold == null ? DEFAULT_GATE_THRESHOLD : gateThreshold;

        if (targetTrackUid != null && !targetTrackUid.isBlank()) {
            return associateExplicit(detection, targetTrackUid, expectedVersion, gate, now);
        }
        return associateAutomatic(detection, gate, now);
    }

    /** 显式关联：必须携带与当前版本一致的 expectedVersion，按 CAS 原子更新。 */
    private AssociateResponse associateExplicit(Detection detection, String targetTrackUid,
                                                Long expectedVersion, double gate, long now) {
        if (expectedVersion == null) {
            throw new BusinessException("EXPECTED_VERSION_REQUIRED",
                    "显式关联必须提供 expectedVersion");
        }
        keyLock.lockUntilTransactionComplete("track:" + targetTrackUid);
        TrackSnapshot track = requireTrackLocked(targetTrackUid);

        // 乐观锁断言优先：携带的 expectedVersion 与当前版本不一致即冲突（409），
        // 与乱序/门限等数据校验无关——客户端必须基于最新版本重试。
        if (track.version() != expectedVersion) {
            throw new VersionConflictException(targetTrackUid, expectedVersion, track.version());
        }

        // 乱序：事件时间早于轨迹最近观测，版本/最近观测/状态一律不得回退。
        if (detection.timestamp() < track.lastObservedAt()) {
            return reject(detection, "OUT_OF_ORDER",
                    "检测时戳 " + detection.timestamp() + " 早于最近观测 "
                            + track.lastObservedAt(), now);
        }
        if (!detection.category().equals(track.category())) {
            return reject(detection, "CATEGORY_MISMATCH",
                    "检测类别 " + detection.category() + " 与轨迹类别 "
                            + track.category() + " 不一致", now);
        }
        if (track.status().isTerminal()) {
            return reject(detection, "ILLEGAL_STATE",
                    "轨迹 " + targetTrackUid + " 已回收（RECYCLED），不能再关联检测", now);
        }
        Double dist = distance(detection, track);
        if (dist != null && dist > gate) {
            return reject(detection, "OUT_OF_GATE",
                    "检测与轨迹最近观测距离 " + round(dist) + " 米，超过门限 " + gate
                            + " 米；如确认身份切换请使用 ID 切换接口", now);
        }

        TrackStatus toStatus = statusOnObservation(track.status());
        EvidenceType evidenceType = track.status() == TrackStatus.LOST
                ? EvidenceType.REIDENTIFICATION : EvidenceType.OBSERVATION;

        TrackSnapshot updated = applyObservation(track, detection, toStatus, detection.timestamp());
        casUpdate(track.trackUid(), track.version(), updated);

        appendObservationEvents(track, updated, detection, toStatus, evidenceType,
                dist, gate, now, null);

        registerProcessed(detection, updated.trackUid(),
                DetectionOutcome.ASSOCIATED.name(), null, now);
        return new AssociateResponse(DetectionOutcome.ASSOCIATED, updated.trackUid(),
                updated.version(), updated.status().name(),
                null, latestEvidenceForEvent(updated.trackUid(), detection.detectionId()),
                updated);
    }

    /** 自动关联：同类非终态轨迹中取门限内最近者；门限内有多个候选时留交叉证据；无候选则建轨。 */
    private AssociateResponse associateAutomatic(Detection detection, double gate, long now) {
        record Candidate(TrackSnapshot track, double distance) {
        }

        // 类别键锁：串行化同类自动关联的"读候选-判定-建轨/更新"全过程。
        keyLock.lockUntilTransactionComplete("cat:" + detection.category());

        List<TrackSnapshot> candidates = trackRepository.findCandidates(detection.category());

        // 乱序：同类活动轨迹中存在最近观测晚于本检测者，拒绝以免最近观测/状态回退。
        TrackSnapshot laterTrack = candidates.stream()
                .filter(t -> detection.timestamp() < t.lastObservedAt())
                .max(Comparator.comparingLong(TrackSnapshot::lastObservedAt))
                .orElse(null);
        if (laterTrack != null) {
            return reject(detection, "OUT_OF_ORDER",
                    "检测时戳 " + detection.timestamp() + " 早于同类轨迹 "
                            + laterTrack.trackUid() + " 的最近观测 " + laterTrack.lastObservedAt(),
                    now);
        }

        List<Candidate> withinGate = new ArrayList<>();
        for (TrackSnapshot t : candidates) {
            Double d = distance(detection, t);
            if (d != null && d <= gate) {
                withinGate.add(new Candidate(t, d));
            }
        }
        withinGate.sort(Comparator.comparingDouble(Candidate::distance));

        if (withinGate.isEmpty()) {
            return createTrack(detection, now, gate);
        }

        Candidate best = withinGate.get(0);

        // 对最终候选加行锁后重读，防止并发下该轨迹在读取候选后被其他事务更新。
        TrackSnapshot track = requireTrackLocked(best.track().trackUid());
        if (detection.timestamp() < track.lastObservedAt()) {
            return reject(detection, "OUT_OF_ORDER",
                    "检测时戳 " + detection.timestamp() + " 早于轨迹最近观测 "
                            + track.lastObservedAt(), now);
        }
        if (track.status().isTerminal()) {
            return reject(detection, "ILLEGAL_STATE",
                    "轨迹 " + track.trackUid() + " 已回收，不能再关联检测", now);
        }
        double lockedDistance = distance(detection, track);
        if (lockedDistance > gate) {
            return reject(detection, "OUT_OF_GATE",
                    "加锁后检测与轨迹最近观测距离 " + round(lockedDistance)
                            + " 米，超过门限 " + gate + " 米", now);
        }

        // 门限内存在第二个候选 => 目标交叉，双向保留可解释证据。
        TrackSnapshot crossingOther = null;
        if (withinGate.size() > 1) {
            crossingOther = withinGate.get(1).track();
        }

        TrackStatus toStatus = statusOnObservation(track.status());
        EvidenceType evidenceType = track.status() == TrackStatus.LOST
                ? EvidenceType.REIDENTIFICATION : EvidenceType.OBSERVATION;

        TrackSnapshot updated = applyObservation(track, detection, toStatus, detection.timestamp());
        casUpdate(track.trackUid(), track.version(), updated);
        appendObservationEvents(track, updated, detection, toStatus, evidenceType,
                lockedDistance, gate, now, crossingOther);

        if (crossingOther != null) {
            // 交叉另一方也留一条指向最终关联轨迹的证据（该轨迹本身不发生任何变化）。
            AssociationEvidence otherEvidence = new AssociationEvidence(
                    null, crossingOther.trackUid(), EvidenceType.CROSSING, track.trackUid(),
                    detection.detectionId(), detection.timestamp(), crossingOther.version(),
                    round(distance(detection, crossingOther)), gate,
                    "目标交叉：检测同时位于该轨迹门限内，但距离更接近 " + track.trackUid()
                            + "，本次检测关联至后者，该轨迹保持不变", now);
            eventStore.appendEvidence(otherEvidence);
        }

        registerProcessed(detection, updated.trackUid(),
                DetectionOutcome.ASSOCIATED.name(), null, now);
        return new AssociateResponse(DetectionOutcome.ASSOCIATED, updated.trackUid(),
                updated.version(), updated.status().name(),
                null, latestEvidenceForEvent(updated.trackUid(), detection.detectionId()),
                updated);
    }

    /** 创建新轨迹：CREATED 状态在同一请求内随首个观测进入 TRACKING。 */
    private AssociateResponse createTrack(Detection detection, long now, double gate) {
        long number = trackRepository.nextTrackNumber();
        String uid = String.format("T-%06d", number);

        TrackSnapshot created = new TrackSnapshot(
                uid, detection.category(), TrackStatus.CREATED, detection.confidence(),
                null, null, null, null, detection.timestamp(),
                1L, detection.timestamp(), detection.timestamp());
        trackRepository.insert(created);
        eventStore.appendEvent(new TrackEvent(
                null, detection.timestamp(), uid, detection.category(), "TRACK_CREATED",
                null, TrackStatus.CREATED, 1L, null, detection.source(),
                null, null, null, null, null, null, null, null,
                "检测 " + detection.detectionId() + " 未匹配到门限内轨迹，创建新轨迹", null, now));

        TrackSnapshot tracking = applyObservation(created, detection,
                TrackStatus.TRACKING, detection.timestamp());
        casUpdate(uid, created.version(), tracking);
        appendObservationEvents(created, tracking, detection, TrackStatus.TRACKING,
                EvidenceType.OBSERVATION, null, gate, now, null);

        registerProcessed(detection, uid, DetectionOutcome.CREATED.name(), null, now);
        List<AssociationEvidence> evidences =
                latestEvidenceForEvent(uid, detection.detectionId());
        return new AssociateResponse(DetectionOutcome.CREATED, uid, tracking.version(),
                tracking.status().name(), null, evidences, tracking);
    }

    @Transactional
    public TrackSnapshot transitionInTx(String trackUid, long expectedVersion,
                                        String targetStatusName, long eventTime, String reason) {
        long now = nowMillis();
        keyLock.lockUntilTransactionComplete("track:" + trackUid);
        TrackSnapshot track = requireTrackLocked(trackUid);
        TrackStatus target;
        try {
            target = TrackStatus.valueOf(targetStatusName);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("UNKNOWN_STATUS",
                    "未知目标状态：" + targetStatusName);
        }
        if (track.version() != expectedVersion) {
            throw new VersionConflictException(trackUid, expectedVersion, track.version());
        }
        if (!track.status().canTransitionTo(target)) {
            throw new BusinessException("ILLEGAL_TRANSITION",
                    "非法状态迁移：" + track.status() + " -> " + target);
        }
        // 事件时间不得早于最近一次有效变化，防止迟到的迁移请求使状态“回退”。
        if (eventTime < track.updatedAt()) {
            throw new BusinessException("OUT_OF_ORDER",
                    "迁移事件时间 " + eventTime + " 早于轨迹最近变化时间 " + track.updatedAt());
        }

        TrackSnapshot next = withStatus(track, target, eventTime);
        casUpdate(trackUid, expectedVersion, next);
        appendTransitionEvent(track, next, "STATUS_TRANSITION", null, reason, now);
        return next;
    }

    @Transactional
    public AssociateResponse idSwitchInTx(String oldTrackUid, long expectedVersion,
                                          Detection detection) {
        long now = nowMillis();

        keyLock.lockUntilTransactionComplete("det:" + detection.detectionId());
        keyLock.lockUntilTransactionComplete("track:" + oldTrackUid);

        Optional<EventStore.ProcessedDetectionRecord> previous =
                eventStore.findProcessed(detection.detectionId());
        if (previous.isPresent()) {
            return duplicateResponse(previous.get());
        }
        if (detection.confidence() < DEFAULT_CONFIDENCE_THRESHOLD) {
            return reject(detection, "LOW_CONFIDENCE",
                    "置信度 " + detection.confidence() + " 低于门限 "
                            + DEFAULT_CONFIDENCE_THRESHOLD, now);
        }

        TrackSnapshot old = requireTrackLocked(oldTrackUid);
        if (old.version() != expectedVersion) {
            throw new VersionConflictException(oldTrackUid, expectedVersion, old.version());
        }
        if (old.status().isTerminal()) {
            throw new BusinessException("ILLEGAL_STATE",
                    "轨迹 " + oldTrackUid + " 已回收，不能再执行 ID 切换");
        }
        if (detection.timestamp() < old.lastObservedAt()) {
            return reject(detection, "OUT_OF_ORDER",
                    "检测时戳 " + detection.timestamp() + " 早于旧轨迹最近观测 "
                            + old.lastObservedAt(), now);
        }

        // 1) 旧身份合法迁移到终态 RECYCLED，版本原子加一。
        TrackSnapshot recycled = withStatus(old, TrackStatus.RECYCLED, detection.timestamp());
        casUpdate(oldTrackUid, expectedVersion, recycled);
        appendTransitionEvent(old, recycled, "ID_SWITCH_RECYCLED", detection,
                "ID 切换：检测 " + detection.detectionId() + " 判定为新身份，旧轨迹回收", now);
        AssociationEvidence oldEvidence = new AssociationEvidence(
                null, oldTrackUid, EvidenceType.ID_SWITCH, null,
                detection.detectionId(), detection.timestamp(), recycled.version(),
                null, null,
                "ID 切换：检测不再归属本轨迹，轨迹号回收；新身份由检测 "
                        + detection.detectionId() + " 承接", now);
        eventStore.appendEvidence(oldEvidence);

        // 2) 新身份建轨并接收首个观测（CREATED -> TRACKING）。
        long number = trackRepository.nextTrackNumber();
        String newUid = String.format("T-%06d", number);
        TrackSnapshot created = new TrackSnapshot(
                newUid, detection.category(), TrackStatus.CREATED, detection.confidence(),
                null, null, null, null, detection.timestamp(),
                1L, detection.timestamp(), detection.timestamp());
        trackRepository.insert(created);
        eventStore.appendEvent(new TrackEvent(
                null, detection.timestamp(), newUid, detection.category(), "ID_SWITCH_CREATED",
                null, TrackStatus.CREATED, 1L, detection.detectionId(), detection.source(),
                null, null, null, null, EvidenceType.ID_SWITCH, oldTrackUid, null, null,
                "ID 切换：由旧轨迹 " + oldTrackUid + " 切换出新身份", null, now));
        eventStore.appendEvidence(new AssociationEvidence(
                null, newUid, EvidenceType.ID_SWITCH, oldTrackUid,
                detection.detectionId(), detection.timestamp(), 1L, null, null,
                "ID 切换：新身份承接检测，前序身份为 " + oldTrackUid, now));

        TrackSnapshot tracking = applyObservation(created, detection,
                TrackStatus.TRACKING, detection.timestamp());
        casUpdate(newUid, created.version(), tracking);
        appendObservationEvents(created, tracking, detection, TrackStatus.TRACKING,
                EvidenceType.ID_SWITCH, null, DEFAULT_GATE_THRESHOLD, now, null);

        registerProcessed(detection, newUid, DetectionOutcome.CREATED.name(), null, now);
        List<AssociationEvidence> evidences = new ArrayList<>(
                latestEvidenceForEvent(oldTrackUid, detection.detectionId()));
        evidences.addAll(latestEvidenceForEvent(newUid, detection.detectionId()));
        return new AssociateResponse(DetectionOutcome.CREATED, newUid, tracking.version(),
                tracking.status().name(), null, evidences, tracking);
    }

    @Transactional(readOnly = true)
    public TrackSnapshot getTrack(String trackUid) {
        return requireTrack(trackUid);
    }

    @Transactional(readOnly = true)
    public List<TrackSnapshot> listTracks() {
        return trackRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<AssociationEvidence> evidence(String trackUid) {
        requireTrack(trackUid);
        return eventStore.findEvidence(trackUid);
    }

    @Transactional(readOnly = true)
    public List<TrackSnapshot> replayAsOf(Long asOfInclusive) {
        return replayer.replay(eventStore.loadEventsUpTo(asOfInclusive));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private TrackSnapshot requireTrack(String trackUid) {
        return trackRepository.findByUid(trackUid)
                .orElseThrow(() -> new BusinessException("TRACK_NOT_FOUND",
                        "轨迹不存在：" + trackUid));
    }

    /** 写路径：加行锁读取轨迹，保证同一轨迹的并发写事务在数据库层串行化。 */
    private TrackSnapshot requireTrackLocked(String trackUid) {
        return trackRepository.lockByUid(trackUid)
                .orElseThrow(() -> new BusinessException("TRACK_NOT_FOUND",
                        "轨迹不存在：" + trackUid));
    }

    private void casUpdate(String trackUid, long expectedVersion, TrackSnapshot next) {
        int affected = trackRepository.compareAndUpdate(trackUid, expectedVersion, next);
        if (affected == 0) {
            TrackSnapshot current = trackRepository.findByUid(trackUid)
                    .orElseThrow(() -> new BusinessException("TRACK_NOT_FOUND",
                            "轨迹不存在：" + trackUid));
            throw new VersionConflictException(trackUid, expectedVersion, current.version());
        }
    }

    /** 观测驱动的目标状态：LOST 经 REIDENTIFIED（持续态），下次观测再回 TRACKING；OCCLUDED/CREATED 直接回 TRACKING。 */
    private TrackStatus statusOnObservation(TrackStatus current) {
        return switch (current) {
            case LOST -> TrackStatus.REIDENTIFIED;
            case OCCLUDED, CREATED, REIDENTIFIED -> TrackStatus.TRACKING;
            default -> current;
        };
    }

    /** 以检测内容生成观测后的快照（状态由调用方给定），版本加一，最近观测前移。 */
    private TrackSnapshot applyObservation(TrackSnapshot prev, Detection detection,
                                           TrackStatus toStatus, long eventTime) {
        return new TrackSnapshot(
                prev.trackUid(),
                prev.category(),
                toStatus,
                detection.confidence(),
                detection.detectionId(),
                detection.x(),
                detection.y(),
                detection.z(),
                eventTime,
                prev.version() + 1,
                prev.createdAt(),
                eventTime);
    }

    private TrackSnapshot withStatus(TrackSnapshot prev, TrackStatus status, long eventTime) {
        return new TrackSnapshot(
                prev.trackUid(), prev.category(), status, prev.confidence(),
                prev.lastDetectionId(), prev.x(), prev.y(), prev.z(),
                prev.lastObservedAt(), prev.version() + 1, prev.createdAt(), eventTime);
    }

    private void appendObservationEvents(TrackSnapshot before, TrackSnapshot after,
                                         Detection detection, TrackStatus toStatus,
                                         EvidenceType evidenceType, Double distance,
                                         double gate, long now, TrackSnapshot crossingOther) {
        String detail = evidenceType == EvidenceType.REIDENTIFICATION
                ? "LOST 轨迹被检测 " + detection.detectionId() + " 重新识别"
                : "检测 " + detection.detectionId() + " 关联至轨迹，距离 "
                        + (distance == null ? "首观测" : round(distance) + " 米")
                        + "，门限 " + gate + " 米";
        TrackEvent event = new TrackEvent(
                null, detection.timestamp(), after.trackUid(), after.category(),
                "OBSERVATION",
                before.status(),
                toStatus, after.version(), detection.detectionId(), detection.source(),
                detection.confidence(), detection.x(), detection.y(), detection.z(),
                evidenceType,
                crossingOther == null ? null : crossingOther.trackUid(),
                distance == null ? null : round(distance), gate, detail, null, now);
        eventStore.appendEvent(event);

        AssociationEvidence evidence = new AssociationEvidence(
                null, after.trackUid(),
                crossingOther == null ? evidenceType : EvidenceType.CROSSING,
                crossingOther == null ? null : crossingOther.trackUid(),
                detection.detectionId(), detection.timestamp(), after.version(),
                distance == null ? null : round(distance), gate,
                crossingOther == null ? detail
                        : detail + "；门限内另一候选轨迹 " + crossingOther.trackUid()
                        + " 未被选中，判定为目标交叉",
                now);
        eventStore.appendEvidence(evidence);
    }

    private void appendTransitionEvent(TrackSnapshot before, TrackSnapshot after,
                                       String eventType, Detection detection,
                                       String reason, long now) {
        eventStore.appendEvent(new TrackEvent(
                null, after.updatedAt(), after.trackUid(), after.category(), eventType,
                before.status(), after.status(), after.version(),
                detection == null ? null : detection.detectionId(),
                detection == null ? null : detection.source(),
                after.confidence(), after.x(), after.y(), after.z(),
                null, null, null, null, reason, null, now));
    }

    private AssociateResponse reject(Detection detection, String reason,
                                     String message, long now) {
        registerProcessed(detection, null, DetectionOutcome.REJECTED.name(), reason, now);
        return new AssociateResponse(DetectionOutcome.REJECTED, null, null,
                null, reason, List.of(), null);
    }

    private AssociateResponse duplicateResponse(EventStore.ProcessedDetectionRecord record) {
        TrackSnapshot track = record.trackUid() == null
                ? null
                : trackRepository.findByUid(record.trackUid()).orElse(null);
        return new AssociateResponse(DetectionOutcome.DUPLICATE, record.trackUid(),
                track == null ? null : track.version(),
                track == null ? null : track.status().name(),
                record.rejectReason(),
                record.trackUid() == null ? List.of()
                        : eventStore.findEvidence(record.trackUid()),
                track);
    }

    private void registerProcessed(Detection detection, String trackUid,
                                   String result, String rejectReason, long now) {
        eventStore.insertProcessed(new EventStore.ProcessedDetectionRecord(
                detection.detectionId(), trackUid, detection.source(),
                detection.timestamp(), detection.category(), detection.confidence(),
                result, rejectReason, now));
    }

    private List<AssociationEvidence> latestEvidenceForEvent(String trackUid,
                                                             String detectionId) {
        return eventStore.findEvidence(trackUid).stream()
                .filter(e -> detectionId.equals(e.detectionId()))
                .reduce((a, b) -> b)
                .map(List::of)
                .orElseGet(List::of);
    }

    /** 欧氏距离（米）；轨迹尚无观测位置时返回 null（创建场景）。 */
    private Double distance(Detection detection, TrackSnapshot track) {
        if (track.x() == null) {
            return null;
        }
        double dx = detection.x() - track.x();
        double dy = detection.y() - track.y();
        double dz = detection.z() - track.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
