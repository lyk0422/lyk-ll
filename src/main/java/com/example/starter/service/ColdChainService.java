package com.example.starter.service;

import com.example.starter.data.ColdChainRepository;
import com.example.starter.domain.CustodySegment;
import com.example.starter.domain.HandoverRecord;
import com.example.starter.domain.SegmentEvaluator;
import com.example.starter.domain.SegmentStatus;
import com.example.starter.domain.Shipment;
import com.example.starter.domain.TemperatureReading;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.TimeProvider;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.HandoverRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ShipmentCreateRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 冷链运输追溯核心业务：建货件、写读数、交接与分段证据查询。
 *
 * <p>写读数与交接都先对 shipment 行加锁（无操作 UPDATE 行锁），
 * 保证同一货件的写读数与交接形成一致提交顺序。</p>
 */
@Service
public class ColdChainService {

    private final ColdChainRepository repository;
    private final TimeProvider timeProvider;

    public ColdChainService(ColdChainRepository repository, TimeProvider timeProvider) {
        this.repository = repository;
        this.timeProvider = timeProvider;
    }

    /**
     * 创建货件并打开第 0 段。
     */
    @Transactional
    public Shipment createShipment(ShipmentCreateRequest request) {
        if (request.minTempC().compareTo(request.maxTempC()) > 0) {
            throw new UnprocessableException("minTempC must not exceed maxTempC");
        }
        long startTime = request.startTime() != null ? request.startTime() : now();
        Shipment shipment = new Shipment(
                request.shipmentId(),
                request.minTempC(),
                request.maxTempC(),
                request.maxExcursionMinutes(),
                request.maxSampleIntervalMinutes(),
                0,
                startTime);
        try {
            repository.insertShipment(shipment);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("shipment already exists: " + request.shipmentId());
        }
        repository.insertSegment(new CustodySegment(
                null, request.shipmentId(), 0, request.custodian(),
                startTime, null, null, null, SegmentStatus.OPEN));
        return shipment;
    }

    /**
     * 写入温度读数；同序号同参重放返回原结果，改值返回 409。
     * 读数归属于其采样时刻所属的运输段；落在已关闭段序号范围内返回 409。
     * 温度使用 BigDecimal 承载，必为有限数值（NaN/Infinity 在反序列化阶段即被 400 拒绝）。
     */
    @Transactional
    public ReadingResult addReading(String shipmentId, ReadingRequest request) {
        if (!repository.lockShipment(shipmentId)) {
            throw new NotFoundException("shipment not found: " + shipmentId);
        }
        long seq = request.sequenceNo();
        TemperatureReading existing = repository.findReading(shipmentId, seq);
        if (existing != null) {
            if (existing.sampledAt() == request.sampledAt()
                    && existing.temperatureC().compareTo(request.temperatureC()) == 0) {
                return new ReadingResult(existing, true);
            }
            throw new ConflictException("sequence " + seq + " already exists with different value");
        }
        List<CustodySegment> segments = repository.findSegments(shipmentId);
        CustodySegment open = null;
        for (CustodySegment s : segments) {
            if (s.status() == SegmentStatus.OPEN) {
                open = s;
            } else if (s.lastSequence() != null && seq >= s.firstSequence() && seq <= s.lastSequence()) {
                throw new ConflictException(
                        "sequence " + seq + " belongs to closed segment version " + s.version());
            }
        }
        if (open == null) {
            throw new ConflictException("no open segment for shipment: " + shipmentId);
        }
        if (request.sampledAt() < open.startTime()) {
            throw new UnprocessableException(
                    "sampledAt is before open segment start time (time regression)");
        }
        TemperatureReading reading = new TemperatureReading(
                shipmentId, seq, request.sampledAt(), request.temperatureC(), now());
        try {
            repository.insertReading(reading);
        } catch (DuplicateKeyException e) {
            // 并发兜底：另一事务已提交同序号（H2 中失败语句不回滚本事务，可直接重读）。
            TemperatureReading raced = repository.findReading(shipmentId, seq);
            if (raced != null && raced.sampledAt() == request.sampledAt()
                    && raced.temperatureC().compareTo(request.temperatureC()) == 0) {
                return new ReadingResult(raced, true);
            }
            throw new ConflictException("sequence " + seq + " already exists with different value");
        }
        if (open.firstSequence() == null || seq < open.firstSequence()) {
            repository.updateFirstSequence(open.id(), seq);
        }
        if (open.lastSequence() == null || seq > open.lastSequence()) {
            repository.updateLastSequence(open.id(), seq);
        }
        return new ReadingResult(reading, false);
    }

    /**
     * 交接：一个事务内校验、关闭旧段、冻结判定结果、打开新段。
     * 同 requestId 同参返回首次冻结结果；异参（含版本或 lastSequence 变化）返回 409。
     */
    @Transactional
    public HandoverOutcome handover(String shipmentId, HandoverRequest request) {
        if (!repository.lockShipment(shipmentId)) {
            throw new NotFoundException("shipment not found: " + shipmentId);
        }
        Shipment shipment = repository.findShipment(shipmentId);
        String fingerprint = Fingerprints.handoverFingerprint(
                request.expectedVersion(), request.custodian(), request.receiver(),
                request.handoverAt(), request.lastSequence());
        HandoverRecord existing = repository.findHandover(shipmentId, request.requestId());
        if (existing != null) {
            if (existing.fingerprint().equals(fingerprint)) {
                return new HandoverOutcome(
                        existing.closedVersion(), existing.resultStatus(),
                        existing.closedVersion() + 1, existing.receiver(),
                        existing.handoverAt(), existing.lastSequence(), true);
            }
            throw new ConflictException(
                    "requestId " + request.requestId() + " reused with different parameters");
        }
        if (request.expectedVersion() != shipment.version()) {
            throw new ConflictException("version conflict: expected " + request.expectedVersion()
                    + " but current is " + shipment.version());
        }
        CustodySegment open = repository.findOpenSegment(shipmentId);
        if (open == null) {
            throw new ConflictException("no open segment for shipment: " + shipmentId);
        }
        if (!open.custodian().equals(request.custodian())) {
            throw new UnprocessableException("custodian mismatch: open segment custodian is "
                    + open.custodian());
        }
        if (request.handoverAt() < open.startTime()) {
            throw new UnprocessableException("handoverAt is before segment start time (time regression)");
        }
        if (open.firstSequence() == null) {
            throw new UnprocessableException("open segment has no readings to hand over");
        }
        if (request.lastSequence() < open.firstSequence()) {
            throw new UnprocessableException("lastSequence is before segment first sequence");
        }
        if (open.lastSequence() != null && request.lastSequence() < open.lastSequence()) {
            throw new UnprocessableException("lastSequence is below the max received sequence "
                    + open.lastSequence());
        }
        List<TemperatureReading> readings = repository.findReadingsBetween(
                shipmentId, open.firstSequence(), request.lastSequence());
        for (int i = 1; i < readings.size(); i++) {
            if (readings.get(i).sampledAt() < readings.get(i - 1).sampledAt()) {
                throw new UnprocessableException(
                        "sampledAt regresses with sequence (time regression) at sequence "
                                + readings.get(i).sequenceNo());
            }
        }
        if (!readings.isEmpty() && readings.get(readings.size() - 1).sampledAt() > request.handoverAt()) {
            throw new UnprocessableException("handoverAt is before the last sampled time (time regression)");
        }
        SegmentStatus status = SegmentEvaluator.evaluate(
                shipment, open.firstSequence(), request.lastSequence(), readings);
        repository.closeSegment(open.id(), request.handoverAt(), request.lastSequence(), status);
        repository.incrementVersion(shipmentId);
        repository.insertSegment(new CustodySegment(
                null, shipmentId, open.version() + 1, request.receiver(),
                request.handoverAt(), null, null, null, SegmentStatus.OPEN));
        repository.insertHandover(new HandoverRecord(
                null, shipmentId, request.requestId(), fingerprint,
                request.custodian(), request.expectedVersion(), request.receiver(),
                request.handoverAt(), request.lastSequence(), status,
                open.version(), now()));
        return new HandoverOutcome(
                open.version(), status, open.version() + 1, request.receiver(),
                request.handoverAt(), request.lastSequence(), false);
    }

    /**
     * 查询货件及其全部分段证据（段信息与段范围内读数）。
     */
    @Transactional(readOnly = true)
    public ShipmentEvidence evidence(String shipmentId) {
        Shipment shipment = repository.findShipment(shipmentId);
        if (shipment == null) {
            throw new NotFoundException("shipment not found: " + shipmentId);
        }
        List<CustodySegment> segments = repository.findSegments(shipmentId);
        List<SegmentWithReadings> result = segments.stream().map(segment -> {
            List<TemperatureReading> readings;
            if (segment.firstSequence() == null || segment.lastSequence() == null) {
                readings = List.of();
            } else {
                readings = repository.findReadingsBetween(
                        shipmentId, segment.firstSequence(), segment.lastSequence());
            }
            return new SegmentWithReadings(segment, readings);
        }).toList();
        return new ShipmentEvidence(shipment, result);
    }

    /**
     * 分段证据聚合值。
     */
    public record SegmentWithReadings(CustodySegment segment, List<TemperatureReading> readings) {
    }

    /**
     * 货件与全部分段证据。
     */
    public record ShipmentEvidence(Shipment shipment, List<SegmentWithReadings> segments) {
    }

    private long now() {
        return timeProvider.nowMillis();
    }
}
