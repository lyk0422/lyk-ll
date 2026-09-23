package com.example.starter.coldchain.service.impl;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.dto.CreateShipmentRequest;
import com.example.starter.coldchain.dto.HandoverRequest;
import com.example.starter.coldchain.dto.HandoverResponse;
import com.example.starter.coldchain.dto.ReadingRequest;
import com.example.starter.coldchain.dto.ReadingResponse;
import com.example.starter.coldchain.dto.SegmentEvidenceResponse;
import com.example.starter.coldchain.entity.HandoverRecord;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.entity.TemperatureReading;
import com.example.starter.coldchain.entity.TransportSegment;
import com.example.starter.coldchain.exception.ConflictException;
import com.example.starter.coldchain.exception.NotFoundException;
import com.example.starter.coldchain.exception.ValidationException;
import com.example.starter.coldchain.repository.ColdChainRepository;
import com.example.starter.coldchain.service.ColdChainService;
import com.example.starter.coldchain.service.FingerprintUtil;
import com.example.starter.coldchain.service.SegmentEvaluator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 冷链运输交接与温度偏离判定服务实现。
 *
 * <p>写读数与交接均在事务内先对货件行加 {@code FOR UPDATE} 行锁，
 * 从而形成按货件串行化的一致提交顺序；唯一约束兜底序号与 requestId 幂等。
 */
@Service
public class ColdChainServiceImpl implements ColdChainService {

    /** 首段默认起始设备序号。 */
    private static final long FIRST_SEGMENT_FIRST_SEQUENCE = 1L;

    private final ColdChainRepository repository;
    private final Clock clock;

    public ColdChainServiceImpl(ColdChainRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Shipment createShipment(CreateShipmentRequest request) {
        if (!Double.isFinite(request.minTemp()) || !Double.isFinite(request.maxTemp())
                || request.minTemp() > request.maxTemp()) {
            throw new ValidationException("温度闭区间不合法：需为有限数值且下限不大于上限");
        }
        LocalDateTime startTime = request.startTime() != null
                ? request.startTime() : LocalDateTime.now(clock);
        if (repository.findShipment(request.shipmentId()) != null) {
            throw new ConflictException("货件已存在: " + request.shipmentId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Shipment shipment = new Shipment(
                request.shipmentId(),
                request.minTemp(),
                request.maxTemp(),
                request.maxExcursionMinutes(),
                request.maxSampleIntervalSeconds(),
                1,
                now);
        TransportSegment firstSegment = new TransportSegment(
                null,
                request.shipmentId(),
                1,
                request.custodian(),
                startTime,
                null,
                FIRST_SEGMENT_FIRST_SEQUENCE,
                null,
                SegmentStatus.OPEN);
        try {
            repository.insertShipment(shipment);
            repository.insertSegment(firstSegment);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("货件已存在: " + request.shipmentId());
        }
        return shipment;
    }

    @Override
    public Shipment getShipment(String shipmentId) {
        Shipment shipment = repository.findShipment(shipmentId);
        if (shipment == null) {
            throw new NotFoundException("货件不存在: " + shipmentId);
        }
        return shipment;
    }

    @Override
    @Transactional
    public ReadingResponse writeReading(String shipmentId, ReadingRequest request) {
        if (!Double.isFinite(request.temperature())) {
            throw new ValidationException("温度须为有限数值");
        }
        // 货件行锁：与交接事务互斥，保证写入与交接的提交顺序一致。
        Shipment shipment = repository.lockShipment(shipmentId);
        if (shipment == null) {
            throw new NotFoundException("货件不存在: " + shipmentId);
        }

        TemperatureReading existing = repository.findReading(shipmentId, request.sequenceNo());
        if (existing != null) {
            if (sameParams(existing, request)) {
                // 同序号同参重放：返回原结果。
                return ReadingResponse.of(existing, true);
            }
            throw new ConflictException("同序号读数已存在且参数不同（deviceNo/sampledAt/temperature 改值冲突）");
        }

        // 已关闭段拒绝补写其序号范围。
        if (belongsToClosedSegment(shipmentId, request.sequenceNo())) {
            throw new ValidationException("该序号属于已关闭运输段，禁止补写");
        }

        // 时间单调性（允许乱序到达，但采样时间须随序号严格递增）。
        TemperatureReading prev = repository.findReadingBefore(shipmentId, request.sequenceNo());
        if (prev != null && !request.sampledAt().isAfter(prev.sampledAt())) {
            throw new ValidationException("采样时间相对前一序号倒退或相等");
        }
        TemperatureReading next = repository.findReadingAfter(shipmentId, request.sequenceNo());
        if (next != null && !request.sampledAt().isBefore(next.sampledAt())) {
            throw new ValidationException("采样时间相对后一序号倒退或相等");
        }

        TemperatureReading reading = new TemperatureReading(
                null,
                shipmentId,
                request.sequenceNo(),
                request.deviceNo(),
                request.sampledAt(),
                request.temperature());
        try {
            repository.insertReading(reading);
        } catch (DuplicateKeyException e) {
            // 并发下被其它事务抢先写入同序号。
            TemperatureReading winner = repository.findReading(shipmentId, request.sequenceNo());
            if (winner != null && sameParams(winner, request)) {
                return ReadingResponse.of(winner, true);
            }
            throw new ConflictException("同序号读数已存在且参数不同（deviceNo/sampledAt/temperature 改值冲突）");
        }
        return ReadingResponse.of(reading, false);
    }

    @Override
    @Transactional
    public HandoverResponse handover(String shipmentId, HandoverRequest request) {
        // 快速幂等路径：已冻结的 requestId 直接返回首次结果（无需再加锁）。
        HandoverRecord frozen = repository.findHandover(request.requestId());
        if (frozen != null) {
            return replay(shipmentId, request, frozen);
        }

        // 货件行锁：串行化同货件的交接与读数写入。
        Shipment shipment = repository.lockShipment(shipmentId);
        if (shipment == null) {
            throw new NotFoundException("货件不存在: " + shipmentId);
        }
        // 双重检查：可能在拿锁前已有同 requestId 事务提交。
        frozen = repository.findHandover(request.requestId());
        if (frozen != null) {
            return replay(shipmentId, request, frozen);
        }

        // 拿锁后货件当前版本即权威版本；过期请求直接判版本冲突。
        if (shipment.currentVersion() != request.expectedVersion()) {
            throw new ConflictException("版本冲突：当前开放段版本为 " + shipment.currentVersion()
                    + "，请求期望版本为 " + request.expectedVersion());
        }

        TransportSegment open = repository.lockOpenSegment(shipmentId);
        if (open == null) {
            throw new ConflictException("货件不存在开放运输段，无法交接");
        }
        if (open.version() != request.expectedVersion()) {
            throw new ConflictException("版本冲突：当前开放段版本为 " + open.version()
                    + "，请求期望版本为 " + request.expectedVersion());
        }
        if (request.receiver().equals(open.custodian())) {
            throw new ValidationException("保管方不匹配：接收方不得与当前保管方相同");
        }
        if (request.handoverAt().isBefore(open.startTime())) {
            throw new ValidationException("交接时间不得早于本段开始时间（时间倒退）");
        }
        if (request.lastSequence() < open.firstSequence() - 1) {
            throw new ValidationException("lastSequence 不得早于本段首序号");
        }

        List<TemperatureReading> readings = repository.findReadingsBetween(
                shipmentId, open.firstSequence(), request.lastSequence());
        // 交接时间不得早于段内末条读数的采样时间（读数时间随序号单调，末条即最晚）。
        if (!readings.isEmpty()
                && request.handoverAt().isBefore(readings.get(readings.size() - 1).sampledAt())) {
            throw new ValidationException("交接时间早于段内末条读数采样时间（时间倒退）");
        }
        SegmentStatus status = SegmentEvaluator.evaluate(
                shipment, readings, open.firstSequence(), request.lastSequence());

        String fingerprint = FingerprintUtil.sha256(shipmentId, request.expectedVersion(),
                request.receiver(), request.handoverAt(), request.lastSequence());
        LocalDateTime now = LocalDateTime.now(clock);
        HandoverRecord record = new HandoverRecord(
                request.requestId(),
                shipmentId,
                request.expectedVersion(),
                request.receiver(),
                request.handoverAt(),
                request.lastSequence(),
                fingerprint,
                null,
                null,
                now);
        try {
            // 先冻结请求键（与后续关闭/新开在同一事务，任何失败整体回滚）。
            repository.insertHandover(record);
        } catch (DuplicateKeyException e) {
            HandoverRecord winner = repository.findHandover(request.requestId());
            if (winner != null && winner.fingerprint().equals(fingerprint)) {
                return replay(shipmentId, request, winner);
            }
            throw new ConflictException("同 requestId 但请求参数不同（含 expectedVersion/lastSequence 变化）");
        }

        int closed = repository.closeSegment(shipmentId, open.version(),
                request.handoverAt(), request.lastSequence(), status);
        if (closed == 0) {
            throw new ConflictException("版本冲突：运输段已被关闭");
        }
        int incremented = repository.compareAndIncrementVersion(shipmentId, open.version());
        if (incremented == 0) {
            throw new ConflictException("版本冲突：货件版本已变化");
        }
        int newVersion = open.version() + 1;
        TransportSegment newSegment = new TransportSegment(
                null,
                shipmentId,
                newVersion,
                request.receiver(),
                request.handoverAt(),
                null,
                request.lastSequence() + 1,
                null,
                SegmentStatus.OPEN);
        repository.insertSegment(newSegment);
        repository.completeHandover(request.requestId(), status, newVersion);

        return new HandoverResponse(
                request.requestId(),
                shipmentId,
                open.version(),
                status,
                newVersion,
                request.receiver(),
                request.handoverAt(),
                false);
    }

    @Override
    public List<SegmentEvidenceResponse> segmentEvidence(String shipmentId) {
        Shipment shipment = getShipment(shipmentId);
        List<TransportSegment> segments = repository.findAllSegments(shipmentId);
        Long maxSequence = repository.findMaxSequence(shipmentId);
        return segments.stream().map(segment -> {
            long upperBound;
            if (segment.lastSequence() != null) {
                upperBound = segment.lastSequence();
            } else if (maxSequence != null && maxSequence >= segment.firstSequence()) {
                upperBound = maxSequence;
            } else {
                upperBound = segment.firstSequence() - 1;
            }
            List<TemperatureReading> readings = upperBound < segment.firstSequence()
                    ? List.of()
                    : repository.findReadingsBetween(shipmentId, segment.firstSequence(), upperBound);
            List<SegmentEvidenceResponse.ReadingView> views = readings.stream()
                    .map(SegmentEvidenceResponse.ReadingView::from)
                    .toList();
            return new SegmentEvidenceResponse(
                    segment.version(),
                    segment.custodian(),
                    segment.startTime(),
                    segment.endTime(),
                    segment.firstSequence(),
                    segment.lastSequence(),
                    segment.status(),
                    views);
        }).toList();
    }

    /** 同 requestId 重放：校验指纹后返回首次冻结的结果。 */
    private HandoverResponse replay(String shipmentId, HandoverRequest request, HandoverRecord frozen) {
        String fingerprint = FingerprintUtil.sha256(shipmentId, request.expectedVersion(),
                request.receiver(), request.handoverAt(), request.lastSequence());
        if (!frozen.shipmentId().equals(shipmentId) || !frozen.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同 requestId 但请求参数不同（含 expectedVersion/lastSequence 变化）");
        }
        if (frozen.segmentStatus() == null || frozen.newVersion() == null) {
            throw new ConflictException("交接结果尚未冻结完成");
        }
        TransportSegment closedSegment = repository.findSegment(shipmentId, frozen.expectedVersion());
        TransportSegment openedSegment = repository.findSegment(shipmentId, frozen.newVersion());
        if (closedSegment == null || openedSegment == null) {
            throw new NotFoundException("交接关联运输段缺失");
        }
        return new HandoverResponse(
                frozen.requestId(),
                shipmentId,
                frozen.expectedVersion(),
                frozen.segmentStatus(),
                frozen.newVersion(),
                openedSegment.custodian(),
                openedSegment.startTime(),
                true);
    }

    /** 判断序号是否落入任一已关闭段的首末序号闭区间。 */
    private boolean belongsToClosedSegment(String shipmentId, long sequenceNo) {
        return repository.findAllSegments(shipmentId).stream()
                .filter(s -> s.status() != SegmentStatus.OPEN)
                .anyMatch(s -> sequenceNo >= s.firstSequence()
                        && s.lastSequence() != null
                        && sequenceNo <= s.lastSequence());
    }

    private boolean sameParams(TemperatureReading existing, ReadingRequest request) {
        return existing.deviceNo().equals(request.deviceNo())
                && existing.sampledAt().equals(request.sampledAt())
                && existing.temperature() == request.temperature();
    }
}
